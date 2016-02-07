package me.nallar.javapatcher;

import javassist.ClassPool;
import me.nallar.javapatcher.patcher.Patcher;

public class PatcherTest {
	// TODO - proper unit tests?
	public static void main(String[] args) {
		Patcher p = new Patcher(ClassPool.getDefault());
		p.loadPatches(PatcherTest.class.getResourceAsStream("/modpatcher.json"));
	}
}
