package me.nallar.javapatcher.patcher;

import com.google.common.base.Splitter;
import javassist.CannotCompileException;
import javassist.ClassMap;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.AttributeInfo;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Descriptor;
import javassist.bytecode.DuplicateMemberException;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Mnemonic;
import javassist.bytecode.Opcode;
import javassist.expr.Cast;
import javassist.expr.ConstructorCall;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.Handler;
import javassist.expr.Instanceof;
import javassist.expr.MethodCall;
import javassist.expr.NewArray;
import javassist.expr.NewExpr;
import me.nallar.javapatcher.PatcherLog;
import me.nallar.javapatcher.mappings.Mappings;
import me.nallar.javapatcher.mappings.MethodDescription;
import org.omg.CORBA.IntHolder;

import java.io.*;
import java.util.*;

/**
 *
 */
@SuppressWarnings({"MethodMayBeStatic", "ObjectAllocationInLoop", "AnonymousInnerClass", "JavadocReference"})
public class Patches {
	private final ClassPool classPool;
	private final Mappings mappings;

	public Patches(ClassPool classPool, Mappings mappings) {
		this.classPool = classPool;
		this.mappings = mappings;
	}

	private static String classSignatureToName(String signature) {
		//noinspection HardcodedFileSeparator
		return signature.substring(1, signature.length() - 1).replace("/", ".");
	}

	/**
	 * Extends the target class by adding all methods, fields and interfaces from the specified class
	 * @param class
	 */
	@Patch (
			requiredAttributes = "class"
	)
	public void mixin(CtClass ctClass, Map<String, String> attributes) throws NotFoundException {
		String fromClass = attributes.get("class");
		CtClass from = classPool.get(fromClass);
		// TODO implement mixin patcher similar to what sponge does - old prepatcher way is fragile
	}

	public void transformClassStaticMethods(CtClass ctClass, String className) {
		for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
			MethodDescription methodDescription = new MethodDescription(className, ctMethod.getName(), ctMethod.getSignature());
			MethodDescription mapped = mappings.map(methodDescription);
			if (mapped != null && !mapped.name.equals(ctMethod.getName())) {
				if ((ctMethod.getModifiers() & Modifier.STATIC) == Modifier.STATIC) {
					try {
						CtMethod replacement = CtNewMethod.copy(ctMethod, ctClass, null);
						ctMethod.setName(mapped.name);
						replacement.setBody("{return " + mapped.name + "($$);}");
						ctClass.addMethod(replacement);
					} catch (CannotCompileException e) {
						PatcherLog.error("Failed to compile", e);
					}
				} else {
					PatcherLog.error("Would remap " + methodDescription + " -> " + mapped + ", but not static.");
				}
			}
			if (ctMethod.getName().length() == 1) {
				PatcherLog.error("1 letter length name " + ctMethod.getName() + " in " + ctClass.getName());
			}
		}
	}

	/**
	 * Disables a method. Only works on methods with void return type. Use replaceMethod
	 */
	@Patch
	public void disableMethod(CtMethod ctMethod, Map<String, String> attributes) throws NotFoundException, CannotCompileException {
		ctMethod.setBody("{ }");
	}

	/**
	 * Removes the targeted method
	 */
	@Patch
	public void removeMethod(CtMethod ctMethod) throws NotFoundException {
		ctMethod.getDeclaringClass().removeMethod(ctMethod);
	}

	/**
	 * Renames the target method to the given name
	 *
	 * @param name New name for the target method
	 */
	@Patch(
			requiredAttributes = "name"
	)
	public void renameMethod(CtMethod ctMethod, Map<String, String> attributes) {
		ctMethod.setName(attributes.get("name"));
	}

	/**
	 * Adds a method to the target class from a parsed code fragement, eg:
	 * code = public String toString() { return this.x + ", " + this.z; }
	 */
	@Patch(
			requiredAttributes = "code"
	)
	public void addMethod(CtClass ctClass, Map<String, String> attributes) throws CannotCompileException {
		try {
			ctClass.addMethod(CtNewMethod.make(attributes.get("code"), ctClass));
		} catch (DuplicateMemberException e) {
			if (!attributes.containsKey("ignoreDuplicate")) {
				throw e;
			}
		}
	}

	/**
	 * Adds a method to the target class from separate code, returnType, parameterTypes and name attributes
	 *
	 * @param ctClass
	 * @param attributes
	 * @throws NotFoundException
	 * @throws CannotCompileException
	 */
	@Patch(
			requiredAttributes = "code,returnType,name"
	)
	public void addMethodWithGivenTypes(CtClass ctClass, Map<String, String> attributes) throws NotFoundException, CannotCompileException {
		String name = attributes.get("name");
		String return_ = attributes.get("returnType");
		String code = attributes.get("code");
		String parameterNamesList = attributes.get("parameterTypes");
		parameterNamesList = parameterNamesList == null ? "" : parameterNamesList;
		List<CtClass> parameterList = new ArrayList<CtClass>();
		for (String parameterName : Splitter.on(',').trimResults().omitEmptyStrings().split(parameterNamesList)) {
			parameterList.add(classPool.get(parameterName));
		}
		CtMethod newMethod = new CtMethod(classPool.get(return_), name, parameterList.toArray(new CtClass[parameterList.size()]), ctClass);
		newMethod.setBody('{' + code + '}');
		ctClass.addMethod(newMethod);
	}

	/**
	 * Replaces the targeted method's code with the code attribute's code
	 */
	@Patch
	public void replaceMethod(CtBehavior method, Map<String, String> attributes) throws NotFoundException, CannotCompileException, BadBytecode {
		String fromClass = attributes.get("fromClass");
		String code = attributes.get("code");
		String field = attributes.get("field");
		if (field != null) {
			code = code.replace("$field", field);
		}
		if (fromClass != null) {
			String fromMethod = attributes.get("fromMethod");
			CtMethod replacingMethod = fromMethod == null ?
					classPool.get(fromClass).getDeclaredMethod(method.getName(), method.getParameterTypes())
					: MethodDescription.fromString(fromClass, fromMethod).inClass(classPool.get(fromClass));
			replaceMethod((CtMethod) method, replacingMethod);
		} else if (code != null) {
			method.setBody(code);
		} else {
			PatcherLog.error("Missing required attributes for replaceMethod");
		}
	}

	private void replaceMethod(CtMethod oldMethod, CtMethod newMethod) throws CannotCompileException, BadBytecode {
		ClassMap classMap = new ClassMap();
		classMap.put(newMethod.getDeclaringClass().getName(), oldMethod.getDeclaringClass().getName());
		oldMethod.setBody(newMethod, classMap);
		oldMethod.getMethodInfo().rebuildStackMap(classPool);
		oldMethod.getMethodInfo().rebuildStackMapForME(classPool);
	}

	/**
	 * Removes the field `field` from the target class
	 *
	 * @param field Field name to remove
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void removeField(CtClass ctClass, Map<String, String> attributes) throws NotFoundException {
		ctClass.removeField(ctClass.getDeclaredField(attributes.get("field")));
	}

	/**
	 * Removes the field `field` from the target class and removes its initializers
	 *
	 * @param field Field name to remove
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void removeFieldAndInitializers(CtClass ctClass, Map<String, String> attributes) throws CannotCompileException, NotFoundException {
		CtField ctField;
		try {
			ctField = ctClass.getDeclaredField(attributes.get("field"));
		} catch (NotFoundException e) {
			if (!attributes.containsKey("silent")) {
				PatcherLog.error("Couldn't find field " + attributes.get("field"));
			}
			return;
		}
		for (CtBehavior ctBehavior : ctClass.getDeclaredConstructors()) {
			removeInitializers(ctBehavior, ctField);
		}
		CtBehavior ctBehavior = ctClass.getClassInitializer();
		if (ctBehavior != null) {
			removeInitializers(ctBehavior, ctField);
		}
		ctClass.removeField(ctField);
	}

	/**
	 * In the target class, changes the field `field`'s type to the given type
	 */
	@Patch(
			requiredAttributes = "type,field"
	)
	public void changefieldClass(final CtClass ctClass, Map<String, String> attributes) throws CannotCompileException, NotFoundException {
		final String field = attributes.get("field");
		CtField oldField = ctClass.getDeclaredField(field);
		oldField.setName(field + "_old");
		String newType = attributes.get("type");
		CtField ctField = new CtField(classPool.get(newType), field, ctClass);
		ctField.setModifiers(oldField.getModifiers());
		ctClass.addField(ctField);
		Set<CtBehavior> allBehaviours = new HashSet<CtBehavior>();
		Collections.addAll(allBehaviours, ctClass.getDeclaredConstructors());
		Collections.addAll(allBehaviours, ctClass.getDeclaredMethods());
		CtBehavior initializer = ctClass.getClassInitializer();
		if (initializer != null) {
			allBehaviours.add(initializer);
		}
		final boolean remove = attributes.containsKey("remove");
		for (CtBehavior ctBehavior : allBehaviours) {
			ctBehavior.instrument(new ExprEditor() {
				@Override
				public void edit(FieldAccess fieldAccess) throws CannotCompileException {
					if (fieldAccess.getClassName().equals(ctClass.getName()) && fieldAccess.getFieldName().equals(field)) {
						if (fieldAccess.isReader()) {
							if (remove) {
								fieldAccess.replace("$_ = null;");
							} else {
								fieldAccess.replace("$_ = $0." + field + ';');
							}
						} else if (fieldAccess.isWriter()) {
							if (remove) {
								fieldAccess.replace("$_ = null;");
							} else {
								fieldAccess.replace("$0." + field + " = $1;");
							}
						}
					}
				}
			});
		}
	}

	/**
	 * Replaces initialisers of the field `field` in `fieldClass`
	 *
	 * @param field                Field to replace initializers
	 * @param fieldClass           (optional) Type of field
	 * @param classContainingField (optional) Class containing field
	 * @param code                 (optional) Code to replace the initializer with. $_ in code = target field. Defaults to `$_ = new fieldClass();`
	 */
	@Patch(
			requiredAttributes = "field",
			emptyConstructor = false
	)
	public void replaceFieldInitializer(final Object o, Map<String, String> attributes) throws CannotCompileException, NotFoundException {
		final String field = attributes.get("field");
		CtClass ctClass = o instanceof CtClass ? (CtClass) o : null;
		CtBehavior ctBehavior = null;
		if (ctClass == null) {
			ctBehavior = (CtBehavior) o;
			ctClass = ctBehavior.getDeclaringClass();
		}
		String ctFieldClass = attributes.get("classContainingField");
		if (ctFieldClass != null) {
			if (ctClass == o) {
				PatcherLog.warn("Must set methods to run on if using fieldClass.");
				return;
			}
			ctClass = classPool.get(ctFieldClass);
		}
		final CtField ctField = ctClass.getDeclaredField(field);
		String code = attributes.get("code");
		String clazz = attributes.get("fieldClass");
		if (code == null && clazz == null) {
			throw new NullPointerException("Must give code or class");
		}
		final String newInitializer = code == null ? "$_ = new " + clazz + "();" : code;
		Set<CtBehavior> allBehaviours = new HashSet<CtBehavior>();
		if (ctBehavior == null) {
			Collections.addAll(allBehaviours, ctClass.getDeclaredConstructors());
			CtBehavior initializer = ctClass.getClassInitializer();
			if (initializer != null) {
				allBehaviours.add(initializer);
			}
		} else {
			allBehaviours.add(ctBehavior);
		}
		final IntHolder replaced = new IntHolder();
		for (CtBehavior ctBehavior_ : allBehaviours) {
			final Map<Integer, String> newExprType = new HashMap<Integer, String>();
			ctBehavior_.instrument(new ExprEditor() {
				NewExpr lastNewExpr;
				int newPos = 0;

				@Override
				public void edit(NewExpr e) {
					lastNewExpr = null;
					newPos++;
					try {
						if (classPool.get(e.getClassName()).subtypeOf(ctField.getType())) {
							lastNewExpr = e;
						}
					} catch (NotFoundException ignored) {
					}
				}

				@Override
				public void edit(FieldAccess e) {
					NewExpr myLastNewExpr = lastNewExpr;
					lastNewExpr = null;
					if (myLastNewExpr != null && e.getFieldName().equals(field)) {
						newExprType.put(newPos, classSignatureToName(e.getSignature()));
					}
				}

				@Override
				public void edit(MethodCall e) {
					lastNewExpr = null;
				}

				@Override
				public void edit(NewArray e) {
					lastNewExpr = null;
				}

				@Override
				public void edit(Cast e) {
					lastNewExpr = null;
				}

				@Override
				public void edit(Instanceof e) {
					lastNewExpr = null;
				}

				@Override
				public void edit(Handler e) {
					lastNewExpr = null;
				}

				@Override
				public void edit(ConstructorCall e) {
					lastNewExpr = null;
				}
			});
			ctBehavior_.instrument(new ExprEditor() {
				int newPos = 0;

				@Override
				public void edit(NewExpr e) throws CannotCompileException {
					newPos++;
					if (newExprType.containsKey(newPos)) {
						String assignedType = newExprType.get(newPos);
						String block = '{' + newInitializer + '}';
						PatcherLog.trace(assignedType + " at " + e.getFileName() + ':' + e.getLineNumber() + " replaced with " + block);
						e.replace(block);
						replaced.value++;
					}
				}
			});
		}
		if (replaced.value == 0 && !attributes.containsKey("silent")) {
			PatcherLog.error("No field initializers found for replacement");
		}
	}

	/**
	 * Replaces a `new oldClass()` expression in the target class or methods with `new newClass();
	 * oldClass and one of newClass or code must be specified.
	 *
	 * @param oldClass Type of new expression to replace
	 * @param newClass (optional) New type to construct
	 * @param code     (optional) $_ = new newClass();
	 */
	@Patch(
			requiredAttributes = "oldClass",
			emptyConstructor = false
	)
	public void replaceNewExpression(Object o, Map<String, String> attributes) throws CannotCompileException, NotFoundException {
		final String type = attributes.get("oldClass");
		final String code = attributes.get("code");
		final String clazz = attributes.get("newClass");
		if (code == null && clazz == null) {
			throw new NullPointerException("Must give code or class");
		}
		final String newInitializer = code == null ? "$_ = new " + clazz + "();" : code;
		final Set<CtBehavior> allBehaviours = new HashSet<CtBehavior>();
		if (o instanceof CtClass) {
			CtClass ctClass = (CtClass) o;
			allBehaviours.addAll(Arrays.asList(ctClass.getDeclaredBehaviors()));
		} else {
			allBehaviours.add((CtBehavior) o);
		}
		final IntHolder done = new IntHolder();
		for (CtBehavior ctBehavior : allBehaviours) {
			ctBehavior.instrument(new ExprEditor() {
				@Override
				public void edit(NewExpr e) throws CannotCompileException {
					if (e.getClassName().equals(type)) {
						e.replace(newInitializer);
						done.value++;
					}
				}
			});
		}
		if (done.value == 0) {
			PatcherLog.error("No new expressions found for replacement.");
		}
	}

	/**
	 * Marks the field `field` in the target class as volatile
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void setVolatile(CtClass ctClass, Map<String, String> attributes) throws NotFoundException {
		String field = attributes.get("field");
		if (field == null) {
			for (CtField ctField : ctClass.getDeclaredFields()) {
				if (ctField.getType().isPrimitive()) {
					ctField.setModifiers(ctField.getModifiers() | Modifier.VOLATILE);
				}
			}
		} else {
			CtField ctField = ctClass.getDeclaredField(field);
			ctField.setModifiers(ctField.getModifiers() | Modifier.VOLATILE);
		}
	}

	/**
	 * Unmarks the field `field` in the target class as volatile
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void unsetVolatile(CtClass ctClass, Map<String, String> attributes) throws NotFoundException {
		String field = attributes.get("field");
		if (field == null) {
			for (CtField ctField : ctClass.getDeclaredFields()) {
				if (ctField.getType().isPrimitive()) {
					ctField.setModifiers(ctField.getModifiers() & ~Modifier.VOLATILE);
				}
			}
		} else {
			CtField ctField = ctClass.getDeclaredField(field);
			ctField.setModifiers(ctField.getModifiers() & ~Modifier.VOLATILE);
		}
	}

	@Patch(
			name = "final"
	)
	public void setFinal(CtClass ctClass, Map<String, String> attributes) throws NotFoundException {
		String field = attributes.get("field");
		if (field == null) {
			for (CtField ctField : ctClass.getDeclaredFields()) {
				if (ctField.getType().isPrimitive()) {
					ctField.setModifiers(ctField.getModifiers() | Modifier.FINAL);
				}
			}
		} else {
			CtField ctField = ctClass.getDeclaredField(field);
			ctField.setModifiers(ctField.getModifiers() | Modifier.FINAL);
		}
	}

	@Patch(
			emptyConstructor = false
	)
	public void unsetFinal(Object o, Map<String, String> attributes) throws NotFoundException {
		String field = attributes.get("field");
		if (field != null) {
			CtClass ctClass = (CtClass) o;
			CtField ctField = ctClass.getDeclaredField(field);
			ctField.setModifiers(Modifier.clear(ctField.getModifiers(), Modifier.FINAL));
		} else if (o instanceof CtClass) {
			CtClass ctClass = (CtClass) o;
			ctClass.setModifiers(Modifier.setPublic(ctClass.getModifiers()));
			for (CtConstructor ctConstructor : ctClass.getDeclaredConstructors()) {
				setPublic(ctConstructor, Collections.<String, String>emptyMap());
			}
		} else {
			CtBehavior ctBehavior = (CtBehavior) o;
			ctBehavior.setModifiers(Modifier.clear(ctBehavior.getModifiers(), Modifier.FINAL));
		}
	}

	/**
	 * Replaces the target class with the specified class, while remapping names.
	 */
	@Patch(
			requiredAttributes = "class"
	)
	public CtClass replaceClass(CtClass clazz, Map<String, String> attributes) throws NotFoundException, CannotCompileException, BadBytecode {
		String fromClass = attributes.get("class");
		String oldName = clazz.getName();
		clazz.setName(oldName + "_old");
		CtClass newClass = classPool.get(fromClass);
		ClassFile classFile = newClass.getClassFile2();
		if (classFile.getSuperclass().equals(oldName)) {
			classFile.setSuperclass(null);
			for (CtConstructor ctBehavior : newClass.getDeclaredConstructors()) {
				javassist.bytecode.MethodInfo methodInfo = ctBehavior.getMethodInfo2();
				CodeAttribute codeAttribute = methodInfo.getCodeAttribute();
				if (codeAttribute != null) {
					CodeIterator iterator = codeAttribute.iterator();
					int pos = iterator.skipSuperConstructor();
					if (pos >= 0) {
						int mref = iterator.u16bitAt(pos + 1);
						ConstPool constPool = codeAttribute.getConstPool();
						iterator.write16bit(constPool.addMethodrefInfo(constPool.addClassInfo("java.lang.Object"), "<init>", "()V"), pos + 1);
						String desc = constPool.getMethodrefType(mref);
						int num = Descriptor.numOfParameters(desc) + 1;
						pos = iterator.insertGapAt(pos, num, false).position;
						Descriptor.Iterator i$ = new Descriptor.Iterator(desc);
						for (i$.next(); i$.isParameter(); i$.next()) {
							iterator.writeByte(i$.is2byte() ? Opcode.POP2 : Opcode.POP, pos++);
						}
					}
					methodInfo.rebuildStackMapIf6(newClass.getClassPool(), newClass.getClassFile2());
				}
			}
		}
		newClass.setName(oldName);
		newClass.setModifiers(newClass.getModifiers() & ~Modifier.ABSTRACT);
		transformClassStaticMethods(newClass, newClass.getName());
		return newClass;
	}

	/**
	 * Replaces accesses of the specified field of type fieldClass in the target method
	 *
	 * @param field      Field to replace accesses of
	 * @param fieldClass class of the field
	 * @param readCode   (optional) Code to replace field reads with. For example, `$_ = 5;` to make the field reads always get 5.
	 * @param writeCode  (optional) Code to replace field writes with. For example `this.setHasStatus($_);` to call a setter method
	 * @throws CannotCompileException
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void replaceFieldAccess(final CtBehavior ctBehavior, Map<String, String> attributes) throws CannotCompileException {
		final String field = attributes.get("field");
		final String readCode = attributes.get("readCode");
		final String writeCode = attributes.get("writeCode");
		final String clazz = attributes.get("fieldClass");
		final boolean removeAfter = attributes.containsKey("removeAfter");
		if (readCode == null && writeCode == null) {
			throw new IllegalArgumentException("readCode or writeCode must be set");
		}
		final IntHolder replaced = new IntHolder();
		try {
			ctBehavior.instrument(new ExprEditor() {
				@Override
				public void edit(FieldAccess fieldAccess) throws CannotCompileException {
					String fieldName;
					try {
						fieldName = fieldAccess.getFieldName();
					} catch (ClassCastException e) {
						PatcherLog.warn("Can't examine field access at " + fieldAccess.getLineNumber() + " which is a r: " + fieldAccess.isReader() + " w: " + fieldAccess.isWriter());
						return;
					}
					if ((clazz == null || fieldAccess.getClassName().equals(clazz)) && fieldName.equals(field)) {
						replaced.value++;
						if (removeAfter) {
							try {
								removeAfterIndex(ctBehavior, fieldAccess.indexOfBytecode());
							} catch (BadBytecode badBytecode) {
								throw SneakyThrow.throw_(badBytecode);
							}
							throw new ExceptionsArentForControlFlow();
						}
						if (fieldAccess.isWriter() && writeCode != null) {
							fieldAccess.replace(writeCode);
						} else if (fieldAccess.isReader() && readCode != null) {
							fieldAccess.replace(readCode);
							PatcherLog.trace("Replaced in " + ctBehavior + ' ' + fieldName + " read with " + readCode);
						}
					}
				}
			});
		} catch (ExceptionsArentForControlFlow ignored) {
		}
		if (replaced.value == 0 && !attributes.containsKey("silent")) {
			PatcherLog.error("Didn't replace any field accesses.");
		}
	}

	@Patch
	public void replaceMethodCall(final CtBehavior ctBehavior, Map<String, String> attributes) throws CannotCompileException {
		String method_ = attributes.get("method");
		if (method_ == null) {
			method_ = "";
		}
		String className_ = null;
		int dotIndex = method_.lastIndexOf('.');
		if (dotIndex != -1) {
			className_ = method_.substring(0, dotIndex);
			method_ = method_.substring(dotIndex + 1);
		}
		if ("self".equals(className_)) {
			className_ = ctBehavior.getDeclaringClass().getName();
		}
		String index_ = attributes.get("index");
		if (index_ == null) {
			index_ = "-1";
		}

		final String method = method_;
		final String className = className_;
		final String newMethod = attributes.get("newMethod");
		String code_ = attributes.get("code");
		if (code_ == null) {
			code_ = "$_ = $0." + newMethod + "($$);";
		}
		final String code = code_;
		final IntHolder replaced = new IntHolder();
		final int index = Integer.valueOf(index_);
		final boolean removeAfter = attributes.containsKey("removeAfter");

		try {
			ctBehavior.instrument(new ExprEditor() {
				private int currentIndex = 0;

				@Override
				public void edit(MethodCall methodCall) throws CannotCompileException {
					if ((className == null || methodCall.getClassName().equals(className)) && (method.isEmpty() || methodCall.getMethodName().equals(method)) && (index == -1 || currentIndex++ == index)) {
						if (newMethod != null) {
							try {
								CtMethod oldMethod = methodCall.getMethod();
								oldMethod.getDeclaringClass().getDeclaredMethod(newMethod, oldMethod.getParameterTypes());
							} catch (NotFoundException e) {
								return;
							}
						}
						replaced.value++;
						PatcherLog.trace("Replaced call to " + methodCall.getClassName() + '/' + methodCall.getMethodName() + " in " + ctBehavior.getLongName());
						if (removeAfter) {
							try {
								removeAfterIndex(ctBehavior, methodCall.indexOfBytecode());
							} catch (BadBytecode badBytecode) {
								throw SneakyThrow.throw_(badBytecode);
							}
							throw new ExceptionsArentForControlFlow();
						}
						methodCall.replace(code);
					}
				}
			});
		} catch (ExceptionsArentForControlFlow ignored) {
		}
		if (replaced.value == 0 && !attributes.containsKey("silent")) {
			PatcherLog.warn("Didn't find any method calls to replace in " + ctBehavior.getLongName() + ". Class: " + className + ", method: " + method + ", index: " + index);
		}
	}

	/**
	 * Removes all code prior to the `index` occurence of `opcode` in the target method
	 *
	 * @param opcode opcode to remove until
	 * @param index  (optional) index of the opcode to remove until. -1 = every occurence, -2 = first occurence. Defaults to 0th index.
	 * @throws BadBytecode
	 */
	@Patch(
			requiredAttributes = "opcode"
	)
	public void removeCodeUntilOpcode(CtBehavior ctBehavior, Map<String, String> attributes) throws BadBytecode {
		int opcode = Arrays.asList(Mnemonic.OPCODE).indexOf(attributes.get("opcode").toLowerCase());
		String removeIndexString = attributes.get("index");
		int removeIndex = removeIndexString == null ? -1 : Integer.parseInt(removeIndexString);
		int currentIndex = 0;
		PatcherLog.trace("Removing until " + attributes.get("opcode") + ':' + opcode + " at " + removeIndex);
		int removed = 0;
		CtClass ctClass = ctBehavior.getDeclaringClass();
		MethodInfo methodInfo = ctBehavior.getMethodInfo();
		CodeAttribute codeAttribute = methodInfo.getCodeAttribute();
		if (codeAttribute != null) {
			CodeIterator iterator = codeAttribute.iterator();
			while (iterator.hasNext()) {
				int index = iterator.next();
				int op = iterator.byteAt(index);
				if (op == opcode && (removeIndex < 0 || removeIndex == currentIndex++)) {
					for (int i = 0; i <= index; i++) {
						iterator.writeByte(Opcode.NOP, i);
					}
					removed++;
					PatcherLog.trace("Removed until " + index);
					if (removeIndex == -2) {
						break;
					}
				}
			}
			methodInfo.rebuildStackMapIf6(ctClass.getClassPool(), ctClass.getClassFile());
		}
		if (removed == 0) {
			PatcherLog.warn("Didn't remove until " + attributes.get("opcode") + ':' + opcode + " at " + removeIndex + " in " + ctBehavior.getName() + ", no matches.");
		}
	}

	private void removeAfterIndex(CtBehavior ctBehavior, int index) throws BadBytecode {
		PatcherLog.trace("Removed after opcode index " + index + " in " + ctBehavior.getLongName());
		CtClass ctClass = ctBehavior.getDeclaringClass();
		MethodInfo methodInfo = ctBehavior.getMethodInfo2();
		CodeAttribute codeAttribute = methodInfo.getCodeAttribute();
		if (codeAttribute != null) {
			CodeIterator iterator = codeAttribute.iterator();
			int i, length = iterator.getCodeLength() - 1;
			for (i = index; i < length; i++) {
				iterator.writeByte(Opcode.NOP, i);
			}
			iterator.writeByte(Opcode.RETURN, i);
			methodInfo.rebuildStackMapIf6(ctClass.getClassPool(), ctClass.getClassFile2());
		}
	}

	private void insertSuper(CtBehavior ctBehavior) throws CannotCompileException {
		ctBehavior.insertBefore("super." + ctBehavior.getName() + "($$);");
	}

	/**
	 * Removes initializers of the given field in the target class
	 *
	 * @param field field in the target class to remove initializers of
	 */
	@Patch(
			requiredAttributes = "field",
			emptyConstructor = false
	)
	public void removeInitializers(Object o, Map<String, String> attributes) throws NotFoundException, CannotCompileException {
		if (o instanceof CtClass) {
			final CtField ctField = ((CtClass) o).getDeclaredField(attributes.get("field"));
			for (CtBehavior ctBehavior : ((CtClass) o).getDeclaredBehaviors()) {
				removeInitializers(ctBehavior, ctField);
			}
		} else {
			removeInitializers((CtBehavior) o, ((CtBehavior) o).getDeclaringClass().getDeclaredField(attributes.get("field")));
		}
	}

	private void removeInitializers(CtBehavior ctBehavior, final CtField ctField) throws CannotCompileException, NotFoundException {
		replaceFieldInitializer(ctBehavior, CollectionsUtil.<String, String>listToMap(
				"field", ctField.getName(),
				"code", "{ $_ = null; }",
				"silent", "true"));
		replaceFieldAccess(ctBehavior, CollectionsUtil.<String, String>listToMap(
				"field", ctField.getName(),
				"writeCode", "{ }",
				"readCode", "{ $_ = null; }",
				"silent", "true"));
	}

	/**
	 * Replaces accesses of a field in the target class with accesses of a ThreadLocal.
	 *
	 * @param field            Field to replace accesses of
	 * @param threadLocalField Field containing the threadlocal. Can be a fully qualified static field,
	 *                         or a field in the target class
	 */
	@Patch(
			requiredAttributes = "field,threadLocalField,type"
	)
	public void replaceFieldWithThreadLocal(CtClass ctClass, Map<String, String> attributes) throws CannotCompileException {
		final String field = attributes.get("field");
		final String threadLocalField = attributes.get("threadLocalField");
		final String type = attributes.get("type");
		String setExpression_ = attributes.get("setExpression");
		final String setExpression = setExpression_ == null ? '(' + type + ") $1" : setExpression_;
		ctClass.instrument(new ExprEditor() {
			@Override
			public void edit(FieldAccess e) throws CannotCompileException {
				if (e.getFieldName().equals(field)) {
					if (e.isReader()) {
						e.replace("{ $_ = (" + type + ") " + threadLocalField + ".get(); }");
					} else if (e.isWriter()) {
						e.replace("{ " + threadLocalField + ".set(" + setExpression + "); }");
					}
				}
			}
		});
	}

	/**
	 * Sets the target class, or field in target class, or given method to have public access
	 *
	 * @param field (optional) Field in target class to make public
	 */
	@Patch(
			emptyConstructor = false
	)
	public void setPublic(Object o, Map<String, String> attributes) throws NotFoundException {
		String field = attributes.get("field");
		if (field != null) {
			CtClass ctClass = (CtClass) o;
			CtField ctField = ctClass.getDeclaredField(field);
			ctField.setModifiers(Modifier.setPublic(ctField.getModifiers()));
		} else if (o instanceof CtClass) {
			CtClass ctClass = (CtClass) o;
			ctClass.setModifiers(Modifier.setPublic(ctClass.getModifiers()));
			List<Object> toPublic = new ArrayList<Object>();
			if (attributes.containsKey("all")) {
				Collections.addAll(toPublic, ctClass.getDeclaredFields());
				Collections.addAll(toPublic, ctClass.getDeclaredBehaviors());
			} else {
				Collections.addAll(toPublic, ctClass.getDeclaredConstructors());
			}
			for (Object o_ : toPublic) {
				setPublic(o_, Collections.<String, String>emptyMap());
			}
		} else if (o instanceof CtField) {
			CtField ctField = (CtField) o;
			ctField.setModifiers(Modifier.setPublic(ctField.getModifiers()));
		} else {
			CtBehavior ctBehavior = (CtBehavior) o;
			ctBehavior.setModifiers(Modifier.setPublic(ctBehavior.getModifiers()));
		}
	}

	/**
	 * Adds a static initializer block to the target class
	 *
	 * @param code Code in static initializer block
	 */
	@Patch(
			requiredAttributes = "code"
	)
	public void addStaticInitializer(CtClass ctClass, Map<String, String> attributes) throws CannotCompileException {
		ctClass.makeClassInitializer().insertAfter(attributes.get("code"));
	}

	/**
	 * Adds a new initializer for the given field in the target class
	 *
	 * @param field      Field to add an initializer for
	 * @param fieldClass type of the field
	 * @param code       (optional) Expression to initialise the field with, eg `new java.util.ArrayList()`. Defaults to `new fieldClass();`
	 * @param arraySize  (optional) Size of the array. If set, type is an array of fieldClass.
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void addInitializer(CtClass ctClass, Map<String, String> attributes) throws NotFoundException, CannotCompileException, IOException {
		String field = attributes.get("field");
		String clazz = attributes.get("fieldClass");
		String initialise = attributes.get("code");
		String arraySize = attributes.get("arraySize");
		initialise = "{ " + field + " = " + (initialise == null ? ("new " + clazz + (arraySize == null ? "()" : '[' + arraySize + ']')) : initialise) + "; }";
		if ((ctClass.getDeclaredField(field).getModifiers() & Modifier.STATIC) == Modifier.STATIC) {
			ctClass.makeClassInitializer().insertAfter(initialise);
		} else {
			CtMethod runConstructors;
			try {
				runConstructors = ctClass.getDeclaredMethod("runConstructors");
			} catch (NotFoundException e) {
				runConstructors = CtNewMethod.make("public void runConstructors() { }", ctClass);
				ctClass.addMethod(runConstructors);
				ctClass.addField(new CtField(classPool.get("boolean"), "isConstructed", ctClass), CtField.Initializer.constant(false));
				for (CtBehavior ctBehavior : ctClass.getDeclaredConstructors()) {
					ctBehavior.insertAfter("{ if(!this.isConstructed) { this.isConstructed = true; this.runConstructors(); } }");
				}
			}
			runConstructors.insertAfter(initialise);
		}
	}

	/**
	 * Adds a new field of type fieldClass to the target class, initialised with the given code if set
	 *
	 * @param field      Field name to add
	 * @param fieldClass class of the field
	 * @param code       (optional) Initialiser, defaults to `new fieldClass();`
	 */
	@Patch(
			requiredAttributes = "field,fieldClass"
	)
	public void addField(CtClass ctClass, Map<String, String> attributes) throws NotFoundException, CannotCompileException, IOException {
		String field = attributes.get("field");
		String clazz = attributes.get("fieldClass");
		String initialise = attributes.get("code");
		if (initialise == null) {
			initialise = "new " + clazz + "();";
		}
		try {
			CtField ctField = ctClass.getDeclaredField(field);
			PatcherLog.warn(field + " already exists as " + ctField);
			return;
		} catch (NotFoundException ignored) {
		}
		CtClass newType = classPool.get(clazz);
		CtField ctField = new CtField(newType, field, ctClass);
		if (attributes.get("static") != null) {
			ctField.setModifiers(ctField.getModifiers() | Modifier.STATIC);
		}
		ctField.setModifiers(Modifier.setPublic(ctField.getModifiers()));
		if ("none".equalsIgnoreCase(initialise)) {
			ctClass.addField(ctField);
		} else {
			CtField.Initializer initializer = CtField.Initializer.byExpr(initialise);
			ctClass.addField(ctField, initializer);
		}
	}

	/**
	 * Inserts a block of java code at the start of the given method
	 *
	 * @param code Code to insert. Eg:
	 *             {
	 *             if ($1 == 0) { // $1 = parameter 1.
	 *             throw new RuntimeException("First parameter can not be 0");
	 *             }
	 *             }
	 */
	@Patch(
			requiredAttributes = "code"
	)
	public void insertCodeBefore(CtBehavior ctBehavior, Map<String, String> attributes) throws NotFoundException, CannotCompileException, IOException {
		String field = attributes.get("field");
		String code = attributes.get("code");
		if (field != null) {
			code = code.replace("$field", field);
		}
		ctBehavior.insertBefore(code);
	}

	/**
	 * Inserts a block of java code at the end of the given method
	 *
	 * @param code Code to insert. Eg:
	 *             {
	 *             System.out.println("Parameter 2 is set to " + $0);
	 *             }
	 */
	@Patch(
			requiredAttributes = "code"
	)
	public void insertCodeAfter(CtBehavior ctBehavior, Map<String, String> attributes) throws NotFoundException, CannotCompileException, IOException {
		String field = attributes.get("field");
		String code = attributes.get("code");
		if (field != null) {
			code = code.replace("$field", field);
		}
		ctBehavior.insertAfter(code, attributes.containsKey("finally"));
	}


	/**
	 * Locks and unlocks the lock in the given field at the start and end of the given method
	 *
	 * @param Field containing Lock
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void lock(CtMethod ctMethod, Map<String, String> attributes) throws NotFoundException, CannotCompileException, IOException {
		String field = attributes.get("field");
		ctMethod.insertBefore("this." + field + ".lock();");
		ctMethod.insertAfter("this." + field + ".unlock();", true);
	}

	/**
	 * In the target method, wraps calls to the method `method` with lock/unlock calls
	 *
	 * @param method name of method to lock calls to
	 * @param field  field containing Lock
	 * @param index  (optional) Index of call to lock. Defaults to all.
	 * @throws CannotCompileException
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void lockMethodCall(final CtBehavior ctBehavior, Map<String, String> attributes) throws CannotCompileException {
		String method_ = attributes.get("method");
		if (method_ == null) {
			method_ = "";
		}
		String className_ = null;
		int dotIndex = method_.indexOf('.');
		if (dotIndex != -1) {
			className_ = method_.substring(0, dotIndex);
			method_ = method_.substring(dotIndex + 1);
		}
		String index_ = attributes.get("index");
		if (index_ == null) {
			index_ = "-1";
		}

		final String method = method_;
		final String className = className_;
		final String field = attributes.get("field");
		final int index = Integer.valueOf(index_);
		final IntHolder replaced = new IntHolder();

		ctBehavior.instrument(new ExprEditor() {
			private int currentIndex = 0;

			@Override
			public void edit(MethodCall methodCall) throws CannotCompileException {
				if ((className == null || methodCall.getClassName().equals(className)) && (method.isEmpty() || methodCall.getMethodName().equals(method)) && (index == -1 || currentIndex++ == index)) {
					PatcherLog.trace("Replaced " + methodCall.getMethodName() + " from " + ctBehavior);
					methodCall.replace("{ " + field + ".lock(); try { $_ =  $proceed($$); } finally { " + field + ".unlock(); } }");
					replaced.value++;
				}
			}
		});
		if (replaced.value == 0) {
			PatcherLog.warn("0 replacements made locking method call " + attributes.get("method") + " in " + ctBehavior.getLongName());
		}
	}

	/**
	 * In the target method, wraps calls to the method `method` with a synchronized block
	 *
	 * @param method name of method to lock calls to
	 * @param field  field containing lock object
	 * @param index  (optional) Index of call to lock. Defaults to all.
	 * @throws CannotCompileException
	 */
	@Patch(
			requiredAttributes = "field"
	)
	public void synchronizeMethodCall(final CtBehavior ctBehavior, Map<String, String> attributes) throws CannotCompileException {
		String method_ = attributes.get("method");
		if (method_ == null) {
			method_ = "";
		}
		String className_ = null;
		int dotIndex = method_.indexOf('.');
		if (dotIndex != -1) {
			className_ = method_.substring(0, dotIndex);
			method_ = method_.substring(dotIndex + 1);
		}
		String index_ = attributes.get("index");
		if (index_ == null) {
			index_ = "-1";
		}

		final String method = method_;
		final String className = className_;
		final String field = attributes.get("field");
		final int index = Integer.valueOf(index_);
		final IntHolder replaced = new IntHolder();

		ctBehavior.instrument(new ExprEditor() {
			private int currentIndex = 0;

			@Override
			public void edit(MethodCall methodCall) throws CannotCompileException {
				if ((className == null || methodCall.getClassName().equals(className)) && (method.isEmpty() || methodCall.getMethodName().equals(method)) && (index == -1 || currentIndex++ == index)) {
					PatcherLog.trace("Replaced " + methodCall.getMethodName() + " from " + ctBehavior);
					methodCall.replace("synchronized(" + field + ") { $_ =  $0.$proceed($$); }");
					replaced.value++;
				}
			}
		});

		if (replaced.value == 0) {
			PatcherLog.warn("0 replacements made synchronizing method call " + attributes.get("method") + " in " + ctBehavior.getLongName());
		}
	}

	/**
	 * Makes the target method, field, or all fields in the target class synchronized
	 *
	 * @param field  Field to synchronize on
	 * @param static (optional) defaults to false. Whether to synchronize static fields, or non-static fields when target is a class
	 * @throws CannotCompileException
	 */
	@Patch(
			emptyConstructor = false
	)
	public void setSynchronized(Object o, Map<String, String> attributes) throws CannotCompileException {
		//noinspection StatementWithEmptyBody
		if (o instanceof CtConstructor) {
		} else if (o instanceof CtMethod) {
			synchronize((CtMethod) o, attributes.get("field"));
		} else {
			int synchronized_ = 0;
			boolean static_ = attributes.containsKey("static");
			for (CtMethod ctMethod : ((CtClass) o).getDeclaredMethods()) {
				boolean isStatic = (ctMethod.getModifiers() & Modifier.STATIC) == Modifier.STATIC;
				if (isStatic == static_) {
					synchronize(ctMethod, attributes.get("field"));
					synchronized_++;
				}
			}
			if (synchronized_ == 0) {
				PatcherLog.error("Nothing synchronized - did you forget the 'static' attribute?");
			} else {
				PatcherLog.trace("Synchronized " + synchronized_ + " methods in " + ((CtClass) o).getName());
			}
		}
	}

	@Patch
	public void unsetSynchronized(CtBehavior ctBehavior) {
		ctBehavior.setModifiers(ctBehavior.getModifiers() & ~Modifier.SYNCHRONIZED);
	}

	private void synchronize(CtMethod ctMethod, String field) throws CannotCompileException {
		if (field == null) {
			int currentModifiers = ctMethod.getModifiers();
			if (Modifier.isSynchronized(currentModifiers)) {
				PatcherLog.warn("Method: " + ctMethod.getLongName() + " is already synchronized");
			} else {
				ctMethod.setModifiers(currentModifiers | Modifier.SYNCHRONIZED);
			}
		} else {
			CtClass ctClass = ctMethod.getDeclaringClass();
			CtMethod replacement = CtNewMethod.copy(ctMethod, ctClass, null);
			int i = 0;
			try {
				//noinspection InfiniteLoopStatement
				for (; true; i++) {
					ctClass.getDeclaredMethod(ctMethod.getName() + "_sync" + i);
				}
			} catch (NotFoundException ignored) {
			}
			ctMethod.setName(ctMethod.getName() + "_sync" + i);
			@SuppressWarnings("unchecked") List<AttributeInfo> attributes = ctMethod.getMethodInfo().getAttributes();
			Iterator<AttributeInfo> attributeInfoIterator = attributes.iterator();
			while (attributeInfoIterator.hasNext()) {
				AttributeInfo attributeInfo = attributeInfoIterator.next();
				if (attributeInfo instanceof AnnotationsAttribute) {
					attributeInfoIterator.remove();
					replacement.getMethodInfo().addAttribute(attributeInfo);
				}
			}
			replacement.setBody("synchronized(" + field + ") { return " + ctMethod.getName() + "($$); }");
			replacement.setModifiers(replacement.getModifiers() & ~Modifier.SYNCHRONIZED);
			ctClass.addMethod(replacement);
		}
	}

	/**
	 * Catches and discards exceptions in the target method.
	 *
	 * @param exceptionClass class of exception to catch. Defaults to `Throwable`
	 * @param code           Code in the catch block. Defaults to `return;`
	 */
	@Patch
	public void catchAndIgnoreExceptions(CtMethod ctMethod, Map<String, String> attributes) throws CannotCompileException, NotFoundException {
		String returnCode = attributes.get("code");
		if (returnCode == null) {
			returnCode = "return;";
		}
		String exceptionType = attributes.get("exceptionClass");
		if (exceptionType == null) {
			exceptionType = "java.lang.Throwable";
		}
		PatcherLog.trace("Ignoring " + exceptionType + " in " + ctMethod + ", returning with " + returnCode);
		ctMethod.addCatch("{ " + returnCode + '}', classPool.get(exceptionType));
	}

	/**
	 * Convert .lock/.unlock calls in the given method to monitor lock/unlock opcodes
	 */
	@Patch
	public void lockToSynchronized(CtBehavior ctBehavior, Map<String, String> attributes) throws BadBytecode {
		CtClass ctClass = ctBehavior.getDeclaringClass();
		MethodInfo methodInfo = ctBehavior.getMethodInfo();
		CodeAttribute codeAttribute = methodInfo.getCodeAttribute();
		CodeIterator iterator = codeAttribute.iterator();
		ConstPool constPool = codeAttribute.getConstPool();
		int done = 0;
		while (iterator.hasNext()) {
			int pos = iterator.next();
			int op = iterator.byteAt(pos);
			if (op == Opcode.INVOKEINTERFACE) {
				int mref = iterator.u16bitAt(pos + 1);
				if (constPool.getInterfaceMethodrefClassName(mref).endsWith("Lock")) {
					String name = constPool.getInterfaceMethodrefName(mref);
					boolean remove = false;
					if ("lock".equals(name)) {
						remove = true;
						iterator.writeByte(Opcode.MONITORENTER, pos);
					} else if ("unlock".equals(name)) {
						remove = true;
						iterator.writeByte(Opcode.MONITOREXIT, pos);
					}
					if (remove) {
						done++;
						iterator.writeByte(Opcode.NOP, pos + 1);
						iterator.writeByte(Opcode.NOP, pos + 2);
						iterator.writeByte(Opcode.NOP, pos + 3);
						iterator.writeByte(Opcode.NOP, pos + 4);
					}
				}
			} else if (op == Opcode.INVOKEVIRTUAL) {
				int mref = iterator.u16bitAt(pos + 1);
				if (constPool.getMethodrefClassName(mref).endsWith("NativeMutex")) {
					String name = constPool.getMethodrefName(mref);
					boolean remove = false;
					if ("lock".equals(name)) {
						remove = true;
						iterator.writeByte(Opcode.MONITORENTER, pos);
					} else if ("unlock".equals(name)) {
						remove = true;
						iterator.writeByte(Opcode.MONITOREXIT, pos);
					}
					if (remove) {
						done++;
						iterator.writeByte(Opcode.NOP, pos + 1);
						iterator.writeByte(Opcode.NOP, pos + 2);
					}
				}
			}
		}
		methodInfo.rebuildStackMapIf6(ctClass.getClassPool(), ctClass.getClassFile2());
		PatcherLog.trace("Replaced " + done + " lock/unlock calls.");
	}

	private static class ExceptionsArentForControlFlow extends RuntimeException {
		private static final long serialVersionUID = 1;
	}
}
