package com.lazyloadouts;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LazyLoadoutsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LazyLoadoutsPlugin.class);
		RuneLite.main(args);
	}
}
