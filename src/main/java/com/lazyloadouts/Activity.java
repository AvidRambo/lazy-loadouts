package com.lazyloadouts;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Something to gear up for (a boss, a slayer task, a minigame) and the wiki's recommended setups for it
 */
@Data
class Activity
{
	private String name;
	private List<Loadout> loadouts;
	/**
	 * Set when the wiki has no setups for this activity: the name of the general activity its setups
	 * come from
	 */
	private String fallback;
	/**
	 * What to know about the monster that the setups alone don't say
	 */
	private String note;

	/**
	 * Gives every activity without setups of its own those of the activity it falls back on
	 */
	static List<Activity> resolve(List<Activity> activities)
	{
		for (Activity activity : activities)
		{
			if (activity.loadouts == null)
			{
				activity.loadouts = activities.stream()
					.filter(a -> a.name.equals(activity.fallback))
					.findFirst()
					.map(Activity::getLoadouts)
					.orElseThrow(() -> new IllegalStateException("No setups for " + activity.name));
			}
		}
		return activities;
	}

	@Data
	static class Loadout
	{
		private String name;
		/**
		 * Equipment slot name to its tiers, best first. Each tier is the items the wiki ranks as equals.
		 */
		private Map<String, int[][]> slots;
		/**
		 * What the wiki says to bring along, where it says so for this setup
		 */
		private int[] inventory;
	}
}
