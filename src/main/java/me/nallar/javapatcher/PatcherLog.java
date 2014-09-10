package me.nallar.javapatcher;

import java.util.logging.*;

@SuppressWarnings("UnusedDeclaration")
public enum PatcherLog {
	;
	public static final Logger LOGGER = Logger.getLogger("JavaPatcher");

	static {
		LOGGER.setLevel(Level.INFO);
	}

	public static void severe(String msg) {
		severe(msg, null);
	}

	public static void warning(String msg) {
		warning(msg, null);
	}

	public static void info(String msg) {
		info(msg, null);
	}

	public static void config(String msg) {
		config(msg, null);
	}

	public static void fine(String msg) {
		fine(msg, null);
	}

	public static void finer(String msg) {
		finer(msg, null);
	}

	public static void finest(String msg) {
		finest(msg, null);
	}

	public static void severe(String msg, Throwable t) {
		LOGGER.log(Level.SEVERE, msg, t);
	}

	public static void warning(String msg, Throwable t) {
		LOGGER.log(Level.WARNING, msg, t);
	}

	public static void info(String msg, Throwable t) {
		LOGGER.log(Level.INFO, msg, t);
	}

	public static void config(String msg, Throwable t) {
		LOGGER.log(Level.CONFIG, msg, t);
	}

	public static void fine(String msg, Throwable t) {
		LOGGER.log(Level.FINE, msg, t);
	}

	public static void finer(String msg, Throwable t) {
		LOGGER.log(Level.FINER, msg, t);
	}

	public static void finest(String msg, Throwable t) {
		LOGGER.log(Level.FINEST, msg, t);
	}

	public static void log(Level level, Throwable throwable, String s) {
		LOGGER.log(level, s, throwable);
	}
}
