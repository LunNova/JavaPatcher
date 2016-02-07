package me.nallar.javapatcher.patcher;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.io.Files;
import javassist.*;
import lombok.val;
import me.nallar.javapatcher.PatcherLog;
import me.nallar.javapatcher.mappings.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Patcher which uses javassist, a config file and a patcher class to patch arbitrary classes.
 */
public class Patcher {
	private static final String debugPatchedOutput = System.getProperty("patcher.debug", "");
	private static final Splitter idSplitter = Splitter.on("  ").trimResults().omitEmptyStrings();
	private final ClassPool classPool;
	private final Mappings mappings;
	private final Map<String, PatchMethodDescriptor> patchMethods = new HashMap<>();
	private final Multimap<String, ClassPatchDescriptor> patches = MultimapBuilder.hashKeys().arrayListValues().build();
	private final Map<String, byte[]> patchedBytes = new HashMap<>();
	private Object patchClassInstance;

	/**
	 * Creates a patcher instance
	 *
	 * @param classPool Javassist classpool set up with correct classpath containing needed classes
	 */
	public Patcher(ClassPool classPool) {
		this(classPool, Patches.class);
	}

	/**
	 * Creates a patcher instance
	 *
	 * @param classPool    Javassist classpool set up with correct classpath containing needed classes
	 * @param patchesClass Class to instantiate containing @Patch annotated methods
	 */
	public Patcher(ClassPool classPool, Class<?> patchesClass) {
		this(classPool, patchesClass, new DefaultMappings());
	}

	/**
	 * Creates a patcher instance
	 *
	 * @param classPool    Javassist classpool set up with correct classpath containing needed classes
	 * @param patchesClass Class to instantiate containing @Patch annotated methods
	 * @param mappings     Mappings instance
	 */
	public Patcher(ClassPool classPool, Class<?> patchesClass, Mappings mappings) {
		for (Method method : patchesClass.getDeclaredMethods()) {
			for (Annotation annotation : method.getDeclaredAnnotations()) {
				if (annotation instanceof Patch) {
					PatchMethodDescriptor patchMethodDescriptor = new PatchMethodDescriptor(method, (Patch) annotation);
					if (patchMethods.put(patchMethodDescriptor.name, patchMethodDescriptor) != null) {
						PatcherLog.warn("Duplicate @Patch method with name " + patchMethodDescriptor.name);
					}
				}
			}
		}
		this.classPool = classPool;
		this.mappings = mappings;
		try {
			patchClassInstance = patchesClass.getDeclaredConstructors()[0].newInstance(classPool, mappings);
		} catch (Exception e) {
			PatcherLog.error("Failed to instantiate patch class", e);
		}
	}

	private static void saveByteCode(byte[] bytes, String name) {
		if (!debugPatchedOutput.isEmpty()) {
			name = name.replace('.', '/') + ".class";
			File file = new File(debugPatchedOutput + '/' + name);
			//noinspection ResultOfMethodCallIgnored
			file.getParentFile().mkdirs();
			try {
				Files.write(bytes, file);
			} catch (IOException e) {
				PatcherLog.error("Failed to save patched bytes for " + name, e);
			}
		}
	}


	/**
	 * Convenience method which reads a patch from an inputstream then passes it to loadPatches
	 *
	 * @param inputStream input stream to read from
	 */
	public void loadPatches(InputStream inputStream) {
		loadPatches(DomUtil.readInputStreamToString(inputStream));
	}

	/**
	 * Loads patches from the given string.
	 *
	 * Currently XML and JSON are supported
	 *
	 * @param patch patch to load
	 */
	@SuppressWarnings("deprecation")
	public void loadPatches(String patch) {
		switch (patch.charAt(0)) {
			case '<':
				readPatchesFromXmlString(patch);
				break;
			case '[':
			case '{':
				readPatchesFromJsonString(patch);
				break;
			default:
				throw new RuntimeException("Unknown patch format for " + patch);
		}
	}

	@Deprecated
	public void readPatchesFromXmlInputStream(InputStream inputStream) {
		readPatchesFromXmlString(DomUtil.readInputStreamToString(inputStream));
	}

	@Deprecated
	public void readPatchesFromJsonInputStream(InputStream inputStream) {
		readPatchesFromJsonString(DomUtil.readInputStreamToString(inputStream));
	}

	@Deprecated
	public void readPatchesFromJsonString(String json) {
		readPatchesFromXmlString(DomUtil.makePatchXmlFromJson(json));
	}

	@Deprecated
	public void readPatchesFromXmlString(String document) {
		try {
			readPatchesFromXmlDocument(DomUtil.readDocumentFromString(document));
		} catch (IOException | SAXException e) {
			throw new RuntimeException(e);
		}
	}

	public void readPatchesFromXmlDocument(Document document) {
		List<Element> patchGroupElements = DomUtil.children(document.getDocumentElement());
		for (Element patchGroupElement : patchGroupElements) {
			// TODO - rework this. Side-effect of object creation makes this look redundant when it isn't
			loadPatchGroup(patchGroupElement);
		}
	}

	/**
	 * @return The Mappings
	 */
	public Mappings getMappings() {
		return mappings;
	}

	/**
	 * @return The ClassPool
	 */
	public ClassPool getClassPool() {
		return classPool;
	}

	/**
	 * Returns whether the given class will be patched
	 *
	 * @param className Name of the class to check
	 * @return Whether a patch exists for that class
	 */
	public boolean willPatch(String className) {
		return !patches.get(className).isEmpty();
	}

	/**
	 * Patch the class with the given name, if it has a patch associated with it.
	 *
	 * @param className Name of the class
	 * @return Returns patched class if needed, else returns null
	 */
	public byte[] patch(String className) {
		return patch(className, null);
	}

	/**
	 * Patch the class with the given name, if it has a patch associated with it.
	 *
	 * @param className     Name of the class
	 * @param originalBytes original class bytes
	 * @return Returns patched class if needed, else returns original class
	 */
	public synchronized byte[] patch(String className, byte[] originalBytes) {
		byte[] bytes = patchedBytes.get(className);
		if (bytes != null) {
			return bytes;
		}
		val patches = this.patches.get(className);
		if (patches.isEmpty()) {
			return originalBytes;
		}
		try {
			CtClass ctClass = classPool.get(className);
			for (val classPatchDescriptor : patches) {
				ctClass = classPatchDescriptor.runPatches(ctClass);
			}
			bytes = ctClass.toBytecode();
			patchedBytes.put(className, bytes);
			saveByteCode(bytes, className);
			return bytes;
		} catch (Throwable t) {
			PatcherLog.error("Failed to patch " + className + " in patch group " + className + '.', t);
			return originalBytes;
		}
	}

	/**
	 * Writes debug info about this patcher to the debug logger
	 */
	public void logDebugInfo() {
		PatcherLog.info("Logging Patcher debug info of " + patches.size() + " class patches");
		for (ClassPatchDescriptor classPatch : patches.values()) {
			PatcherLog.info(classPatch.toString());
		}
	}

	private void obfuscateAttributesAndTextContent(Element root) {
		// TODO - reimplement environments?
		/*
		for (Element classElement : DomUtil.children(root.getChildNodes())) {
			String env = classElement.getAttribute("env");
			if (env != null && !env.isEmpty()) {
				if (!env.equals(getEnv())) {
					root.removeChild(classElement);
				}
			}
		}
		*/
		for (Element element : DomUtil.children(root)) {
			if (!DomUtil.children(element).isEmpty()) {
				obfuscateAttributesAndTextContent(element);
			} else if (element.getTextContent() != null && !element.getTextContent().isEmpty()) {
				element.setTextContent(mappings.obfuscate(element.getTextContent()));
			}
			Map<String, String> attributes = DomUtil.getAttributes(element);
			for (Map.Entry<String, String> attributeEntry : attributes.entrySet()) {
				element.setAttribute(attributeEntry.getKey(), mappings.obfuscate(attributeEntry.getValue()));
			}
		}
		for (Element element : DomUtil.children(root)) {
			String id = element.getAttribute("id");
			ArrayList<String> list = Lists.newArrayList(idSplitter.split(id));
			if (list.size() > 1) {
				for (String className : list) {
					Element newClassElement = (Element) element.cloneNode(true);
					newClassElement.setAttribute("id", className.trim());
					element.getParentNode().insertBefore(newClassElement, element);
				}
				element.getParentNode().removeChild(element);
			}
		}
	}

	private void loadPatchGroup(Element e) {
		Map<String, String> attributes = DomUtil.getAttributes(e);
		String requiredProperty = attributes.get("requireProperty");
		if (requiredProperty != null && !requiredProperty.isEmpty() && !Boolean.getBoolean(requiredProperty)) {
			// Required property attribute isn't set as system property
			return;
		}
		obfuscateAttributesAndTextContent(e);
		val patchElements = DomUtil.children(e);
		for (Element classElement : patchElements) {
			ClassPatchDescriptor classPatchDescriptor;
			try {
				classPatchDescriptor = new ClassPatchDescriptor(classElement);
			} catch (Throwable t) {
				throw new RuntimeException("Failed to create class patch for " + classElement.getAttribute("id"), t);
			}
			patches.put(classPatchDescriptor.name, classPatchDescriptor);
		}
	}

	private static class PatchDescriptor {
		private final Map<String, String> attributes;
		private final String patch;
		private String methods;

		PatchDescriptor(Element element) {
			attributes = DomUtil.getAttributes(element);
			methods = element.getTextContent().trim();
			patch = element.getTagName();
		}

		public String set(String name, String value) {
			return attributes.put(name, value);
		}

		public String get(String name) {
			return attributes.get(name);
		}

		public Map<String, String> getAttributes() {
			return attributes;
		}

		public String getMethods() {
			return methods;
		}

		public void setMethods(String methods) {
			this.methods = methods;
		}

		public String getPatch() {
			return patch;
		}
	}

	private static class PatchMethodDescriptor {
		public final String name;
		public final List<String> requiredAttributes;
		public final Method patchMethod;
		public final boolean isClassPatch;
		public final boolean emptyConstructor;

		private PatchMethodDescriptor(Method method, Patch patch) {
			String name = patch.name();
			if (Arrays.asList(method.getParameterTypes()).contains(Map.class)) {
				this.requiredAttributes = Lists.newArrayList(Splitter.on(",").trimResults().omitEmptyStrings().split(patch.requiredAttributes()));
			} else {
				this.requiredAttributes = null;
			}
			if (name == null || name.isEmpty()) {
				name = method.getName();
			}
			this.name = name;
			emptyConstructor = patch.emptyConstructor();
			isClassPatch = method.getParameterTypes()[0].equals(CtClass.class);
			patchMethod = method;
		}

		public Object run(PatchDescriptor patchDescriptor, CtClass ctClass, Object patchClassInstance) {
			String methods = patchDescriptor.getMethods();
			Map<String, String> attributes = patchDescriptor.getAttributes();
			Map<String, String> attributesClean = new HashMap<>(attributes);
			attributesClean.remove("code");
			PatcherLog.trace("Patching " + ctClass.getName() + " with " + this.name + '(' + CollectionsUtil.mapToString(attributesClean) + ')' + (methods.isEmpty() ? "" : " {" + methods + '}'));
			if (requiredAttributes != null && !requiredAttributes.isEmpty() && !attributes.keySet().containsAll(requiredAttributes)) {
				PatcherLog.error("Missing required attributes " + requiredAttributes.toString() + " when patching " + ctClass.getName());
				return null;
			}
			if ("^all^".equals(methods)) {
				patchDescriptor.set("silent", "true");
				List<CtBehavior> ctBehaviors = new ArrayList<>();
				Collections.addAll(ctBehaviors, ctClass.getDeclaredMethods());
				Collections.addAll(ctBehaviors, ctClass.getDeclaredConstructors());
				CtBehavior initializer = ctClass.getClassInitializer();
				if (initializer != null) {
					ctBehaviors.add(initializer);
				}
				for (CtBehavior ctBehavior : ctBehaviors) {
					run(ctBehavior, attributes, patchClassInstance);
				}
			} else if (isClassPatch || (!emptyConstructor && methods.isEmpty())) {
				return run(ctClass, attributes, patchClassInstance);
			} else if (methods.isEmpty()) {
				for (CtConstructor ctConstructor : ctClass.getDeclaredConstructors()) {
					run(ctConstructor, attributes, patchClassInstance);
				}
			} else if ("^static^".equals(methods)) {
				CtConstructor ctBehavior = ctClass.getClassInitializer();
				if (ctBehavior == null) {
					PatcherLog.error("No static initializer found patching " + ctClass.getName() + " with " + toString());
				} else {
					run(ctBehavior, attributes, patchClassInstance);
				}
			} else {
				List<MethodDescription> methodDescriptions = MethodDescription.fromListString(ctClass.getName(), methods);
				for (MethodDescription methodDescription : methodDescriptions) {
					CtBehavior ctBehavior;
					try {
						ctBehavior = methodDescription.inClass(ctClass);
					} catch (Throwable t) {
						if (!attributes.containsKey("allowMissing")) {
							PatcherLog.warn("", t);
						}
						continue;
					}
					run(ctBehavior, attributes, patchClassInstance);
				}
			}
			return null;
		}

		private Object run(CtClass ctClass, Map<String, String> attributes, Object patchClassInstance) {
			try {
				if (requiredAttributes == null) {
					return patchMethod.invoke(patchClassInstance, ctClass);
				} else {
					return patchMethod.invoke(patchClassInstance, ctClass, attributes);
				}
			} catch (Throwable t) {
				if (t instanceof InvocationTargetException) {
					t = t.getCause();
				}
				if (t instanceof CannotCompileException && attributes.containsKey("code")) {
					PatcherLog.error("Code: " + attributes.get("code"));
				}
				PatcherLog.error("Error patching " + ctClass.getName() + " with " + toString(), t);
				return null;
			}
		}

		private Object run(CtBehavior ctBehavior, Map<String, String> attributes, Object patchClassInstance) {
			try {
				if (requiredAttributes == null) {
					return patchMethod.invoke(patchClassInstance, ctBehavior);
				} else {
					return patchMethod.invoke(patchClassInstance, ctBehavior, attributes);
				}
			} catch (Throwable t) {
				if (t instanceof InvocationTargetException) {
					t = t.getCause();
				}
				if (t instanceof CannotCompileException && attributes.containsKey("code")) {
					PatcherLog.error("Code: " + attributes.get("code"));
				}
				PatcherLog.error("Error patching " + ctBehavior.getName() + " in " + ctBehavior.getDeclaringClass().getName() + " with " + toString(), t);
				return null;
			}
		}

		@Override
		public String toString() {
			return name;
		}
	}

	public class ClassPatchDescriptor {
		public final String name;
		public final List<PatchDescriptor> patches = new ArrayList<>();
		private final Map<String, String> attributes;

		private ClassPatchDescriptor(Element element) {
			attributes = DomUtil.getAttributes(element);
			ClassDescription deobfuscatedClass = new ClassDescription(attributes.get("id"));
			ClassDescription obfuscatedClass = mappings.map(deobfuscatedClass);
			name = obfuscatedClass == null ? deobfuscatedClass.name : obfuscatedClass.name;
			for (Element patchElement : DomUtil.children(element)) {
				PatchDescriptor patchDescriptor = new PatchDescriptor(patchElement);
				patches.add(patchDescriptor);
				List<MethodDescription> methodDescriptionList = MethodDescription.fromListString(deobfuscatedClass.name, patchDescriptor.getMethods());
				if (!patchDescriptor.getMethods().isEmpty()) {
					patchDescriptor.set("deobf", methodDescriptionList.get(0).getShortName());
					patchDescriptor.setMethods(MethodDescription.toListString(mappings.map(methodDescriptionList)));
				}
				String field = patchDescriptor.get("field"), prefix = "";
				if (field != null && !field.isEmpty()) {
					if (field.startsWith("this.")) {
						field = field.substring("this.".length());
						prefix = "this.";
					}
					String after = "", type = name;
					if (field.indexOf('.') != -1) {
						after = field.substring(field.indexOf('.'));
						field = field.substring(0, field.indexOf('.'));
						if (!field.isEmpty() && (field.charAt(0) == '$') && prefix.isEmpty()) {
							ArrayList<String> parameterList = new ArrayList<>();
							for (MethodDescription methodDescriptionOriginal : methodDescriptionList) {
								MethodDescription methodDescription = mappings.unmap(mappings.map(methodDescriptionOriginal));
								methodDescription = methodDescription == null ? methodDescriptionOriginal : methodDescription;
								int i = 0;
								for (String parameter : methodDescription.getParameterList()) {
									if (parameterList.size() <= i) {
										parameterList.add(parameter);
									} else if (!parameterList.get(i).equals(parameter)) {
										parameterList.set(i, null);
									}
									i++;
								}
							}
							int parameterIndex = Integer.valueOf(field.substring(1)) - 1;
							if (parameterIndex >= parameterList.size()) {
								if (!parameterList.isEmpty()) {
									PatcherLog.error("Can not obfuscate parameter field " + patchDescriptor.get("field") + ", index: " + parameterIndex + " but parameter list is: " + Joiner.on(',').join(parameterList));
								}
								break;
							}
							type = parameterList.get(parameterIndex);
							if (type == null) {
								PatcherLog.error("Can not obfuscate parameter field " + patchDescriptor.get("field") + " automatically as this parameter does not have a single type across the methods used in this patch.");
								break;
							}
							prefix = field + '.';
							field = after.substring(1);
							after = "";
						}
					}
					FieldDescription obfuscatedField = mappings.map(new FieldDescription(type, field));
					if (obfuscatedField != null) {
						patchDescriptor.set("field", prefix + obfuscatedField.name + after);
					}
				}
			}
		}

		public CtClass runPatches(CtClass ctClass) throws NotFoundException {
			for (PatchDescriptor patchDescriptor : patches) {
				PatchMethodDescriptor patchMethodDescriptor = patchMethods.get(patchDescriptor.getPatch());
				if (patchMethodDescriptor == null) {
					PatcherLog.error("Couldn't find patch with name " + patchDescriptor.getPatch() + " when patching " + ctClass.getName());
					return ctClass;
				}
				Object result = patchMethodDescriptor.run(patchDescriptor, ctClass, patchClassInstance);
				if (result instanceof CtClass) {
					ctClass = (CtClass) result;
				}
			}
			return ctClass;
		}
	}
}
