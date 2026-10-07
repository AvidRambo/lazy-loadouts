package com.lazyloadouts;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntPredicate;
import lombok.Value;

/**
 * How much of the best gear the wiki lists the player has, over every setup of every activity
 */
@Value
class Progress
{
	/**
	 * Gear slots over all setups, and in how many of them the player owns an item of the top tier
	 */
	int slots;
	int best;
	/**
	 * Top tier items the player lacks, to the number of setups they would complete a slot of, most first
	 */
	Map<Integer, Integer> missing;

	static Progress of(Collection<Activity.Loadout> setups, IntPredicate owned)
	{
		int slots = 0;
		int best = 0;
		Map<Integer, Integer> counts = new HashMap<>();
		for (Activity.Loadout setup : setups)
		{
			for (Map.Entry<String, int[][]> e : setup.getSlots().entrySet())
			{
				// special attack weapons are extras, not a slot to fill
				if (e.getKey().equals(LadderLayout.SPECIAL))
				{
					continue;
				}

				int[] top = e.getValue()[0];
				++slots;
				if (Arrays.stream(top).anyMatch(owned))
				{
					++best;
				}
				else
				{
					// any of the tier would do, so the first stands for it
					counts.merge(top[0], 1, Integer::sum);
				}
			}
		}

		Map<Integer, Integer> missing = new LinkedHashMap<>();
		counts.entrySet().stream()
			.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.forEach(e -> missing.put(e.getKey(), e.getValue()));
		return new Progress(slots, best, missing);
	}
}
