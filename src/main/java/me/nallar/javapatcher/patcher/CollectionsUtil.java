package me.nallar.javapatcher.patcher;

import java.util.*;

enum CollectionsUtil {
	;

	public static String mapToString(Map<?, ?> map) {
		if (map.isEmpty()) {
			return "";
		}
		StringBuilder stringBuilder = new StringBuilder();
		boolean notFirst = false;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (notFirst) {
				stringBuilder.append(',');
			}
			stringBuilder.append(entry.getKey().toString()).append(':').append(entry.getValue().toString());
			notFirst = true;
		}
		return stringBuilder.toString();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <K, V> Map<K, V> listToMap(Object... objects) {
		HashMap map = new HashMap();
		Object key = null;
		for (final Object object : objects) {
			if (key == null) {
				key = object;
			} else {
				map.put(key, object);
				key = null;
			}
		}
		return map;
	}
}
