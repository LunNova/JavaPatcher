package me.nallar.javapatcher.patcher;

/**
 * Usage:
 * catch (CheckedException e) {
 * throw Throw.sneaky(e);
 * }
 */
@SuppressWarnings("unchecked")
public enum Throw {
	;

	/**
	 * Throws the passed throwable. Does not return. Return type given so
	 * catch (CheckedException e) {
	 * throw Throw.sneaky(e);
	 * }
	 * can be used.
	 *
	 * @param throwable Throwable to throw
	 * @return Never returns.
	 */
	public static RuntimeException sneaky(Throwable throwable) {
		throw Throw.<RuntimeException>throwIgnoreCheckedErasure(throwable);
	}

	private static <T extends Throwable> RuntimeException throwIgnoreCheckedErasure(Throwable toThrow) throws T {
		throw (T) toThrow;
	}
}
