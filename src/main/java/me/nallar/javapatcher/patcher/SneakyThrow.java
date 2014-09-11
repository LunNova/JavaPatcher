package me.nallar.javapatcher.patcher;

/**
 * Usage:
 * catch (CheckedException e) {
 * throw SneakyThrow.throw_(e);
 * }
 */
@SuppressWarnings("unchecked")
public enum SneakyThrow {
	;

	/**
	 * Throws the passed throwable. Does not return. Return type given so
	 * catch (CheckedException e) {
	 * throw SneakyThrow.throw_(e);
	 * }
	 * can be used.
	 *
	 * @param throwable Throwable to throw
	 * @return Never returns.
	 */
	public static RuntimeException throw_(Throwable throwable) {
		throw SneakyThrow.<RuntimeException>throwIgnoreCheckedErasure(throwable);
	}

	private static <T extends Throwable> RuntimeException throwIgnoreCheckedErasure(Throwable toThrow) throws T {
		throw (T) toThrow;
	}
}
