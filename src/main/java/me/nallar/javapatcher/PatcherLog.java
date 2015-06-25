package me.nallar.javapatcher;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * For internal use only, used to Log patcher errors/warnings/info.
 */
public class PatcherLog {
	/*
	 * This class might get classloaded twice under different classloaders. Don't do anything important in a static {} block.
	 */
	public static final Logger LOGGER = LogManager.getLogger("JavaPatcher");

	public static void error(String msg) {
		LOGGER.error(msg);
	}

	public static void warn(String msg) {
		LOGGER.warn(msg);
	}

	public static void info(String msg) {
		LOGGER.info(msg);
	}

	public static void trace(String msg) {
		LOGGER.trace(msg);
	}

	public static void error(String msg, Throwable t) {
		LOGGER.log(Level.ERROR, msg, t);
	}

	public static void warn(String msg, Throwable t) {
		LOGGER.log(Level.WARN, msg, t);
	}

	public static void info(String msg, Throwable t) {
		LOGGER.log(Level.INFO, msg, t);
	}

	public static void trace(String msg, Throwable t) {
		LOGGER.log(Level.TRACE, msg, t);
	}

	public static String classString(Object o) {
		return "c " + o.getClass().getName() + ' ';
	}

	public static void log(Level level, Throwable throwable, String s) {
		LOGGER.log(level, s, throwable);
	}
}
