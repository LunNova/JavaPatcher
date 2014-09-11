package me.nallar.javapatcher.mappings;

import java.util.*;

/**
 * Maps method/field/class names in patches to allow obfuscated code to be patched.
 */
public abstract class Mappings {
	/**
	 * Takes a list of Class/Method/FieldDescriptions and maps them all using the appropriate map(*Description)
	 *
	 * @param list List of Class/Method/FieldDescriptions
	 * @return Mapped List
	 */
	@SuppressWarnings("unchecked")
	public final <T> List<T> map(List<T> list) {
		List<T> mappedThings = new ArrayList<T>();
		for (Object thing : list) {
			// TODO - cleaner way of doing this?
			if (thing instanceof MethodDescription) {
				mappedThings.add((T) map((MethodDescription) thing));
			} else if (thing instanceof ClassDescription) {
				mappedThings.add((T) map((ClassDescription) thing));
			} else if (thing instanceof FieldDescription) {
				mappedThings.add((T) map((FieldDescription) thing));
			} else {
				throw new IllegalArgumentException("Must be mappable: " + thing + "isn't!");
			}
		}
		return mappedThings;
	}

	public abstract MethodDescription map(MethodDescription methodDescription);

	public abstract ClassDescription map(ClassDescription classDescription);

	public abstract FieldDescription map(FieldDescription fieldDescription);

	public abstract MethodDescription unmap(MethodDescription methodDescription);

	public abstract String obfuscate(String code);
}
