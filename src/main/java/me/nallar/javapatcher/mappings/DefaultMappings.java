package me.nallar.javapatcher.mappings;

/**
 * Default mappings class which does nothing.
 */
public class DefaultMappings extends Mappings {
	@Override
	public MethodDescription map(MethodDescription methodDescription) {
		return methodDescription;
	}

	@Override
	public ClassDescription map(ClassDescription classDescription) {
		return classDescription;
	}

	@Override
	public FieldDescription map(FieldDescription fieldDescription) {
		return fieldDescription;
	}

	@Override
	public MethodDescription unmap(MethodDescription methodDescription) {
		return methodDescription;
	}

	@Override
	public String obfuscate(String code) {
		return code;
	}
}
