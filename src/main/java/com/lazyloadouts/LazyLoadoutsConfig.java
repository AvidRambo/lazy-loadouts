package com.lazyloadouts;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(LazyLoadoutsConfig.GROUP)
public interface LazyLoadoutsConfig extends Config
{
	String GROUP = "lazyloadouts";
	String ONLY_OWNED = "onlyOwned";

	@ConfigItem(
		keyName = ONLY_OWNED,
		name = "Show only owned",
		description = "Leave the items you don't have out of the bank tab, instead of showing them faded",
		position = 1
	)
	default boolean onlyOwned()
	{
		return false;
	}

	@Range(max = Integer.MAX_VALUE)
	@ConfigItem(
		keyName = "upgradeBudget",
		name = "Upgrade budget (gp)",
		description = "Suggest the best upgrade per slot that costs no more than this. 0 suggests the next step up, whatever it costs.",
		position = 2
	)
	default int upgradeBudget()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "markUnwearable",
		name = "Mark gear above your level",
		description = "Outline in red the items you don't have the levels to equip, and leave them out of upgrade suggestions",
		position = 3
	)
	default boolean markUnwearable()
	{
		return true;
	}
}
