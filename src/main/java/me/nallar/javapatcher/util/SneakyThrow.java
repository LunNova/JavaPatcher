package me.nallar.javapatcher.util;

/**
 * Usage:
 * catch (CheckedException e) {
 * throw SneakyThrow.throw_(e);
 * }
 */
@SuppressWarnings("unchecked")
public enum SneakyThrow {
	;

	public static RuntimeException throw_(Throwable t) {
		throw SneakyThrow.<RuntimeException>throwIgnoreCheckedErasure(t);
	}

	private static <T extends Throwable> RuntimeException throwIgnoreCheckedErasure(Throwable toThrow) throws T {
		throw (T) toThrow;
	}
}
