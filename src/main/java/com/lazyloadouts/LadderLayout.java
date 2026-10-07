package com.lazyloadouts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import lombok.Value;

/**
 * Lays a loadout out as one bank row per equipment slot, best item on the left. Items the player
 * doesn't own stay in the row, where Bank Tags draws them faded, so the row doubles as an upgrade path.
 */
class LadderLayout
{
	static final int COLUMNS = 8;
	/**
	 * Rows top to bottom. A slot always gets the same row, even when a loadout has nothing for it.
	 */
	static final String SPECIAL = "special";
	static final String[] SLOTS = {
		"head", "cape", "neck", "ammo", "weapon", "body", "shield", "legs", "hands", "feet", "ring", SPECIAL
	};
	/**
	 * A tier with more equals than this is a family of lookalikes (god blessings, blessed d'hide),
	 * of which only the ones the player owns are worth showing
	 */
	private static final int MAX_EQUALS = 3;

	@Value
	static class Ladder
	{
		/**
		 * Item id at each bank position, -1 where there is none
		 */
		int[] layout;
		/**
		 * The tier of the item at each position, 1 being the best
		 */
		int[] tier;
	}

	/**
	 * @param onlyOwned leave out what the player doesn't own instead of keeping it as an upgrade to see
	 */
	static Ladder build(Map<String, int[][]> slots, IntPredicate owned, boolean onlyOwned)
	{
		int[] layout = new int[SLOTS.length * COLUMNS];
		int[] tierAt = new int[layout.length];
		Arrays.fill(layout, -1);

		for (int row = 0; row < SLOTS.length; ++row)
		{
			int[][] tiers = slots.get(SLOTS[row]);
			if (tiers == null)
			{
				continue;
			}

			List<Integer> items = new ArrayList<>();
			List<Integer> itemTiers = new ArrayList<>();
			for (int t = 0; t < tiers.length; ++t)
			{
				int[] tier = tiers[t];
				int before = items.size();
				for (int itemId : tier)
				{
					if (owned.test(itemId) || (!onlyOwned && tier.length <= MAX_EQUALS))
					{
						items.add(itemId);
					}
				}
				if (!onlyOwned && items.size() == before && tier.length > 0)
				{
					items.add(tier[0]);
				}
				while (itemTiers.size() < items.size())
				{
					itemTiers.add(t + 1);
				}
			}

			// A row that is too long loses the worst items the player doesn't own
			for (int i = items.size() - 1; i >= 0 && items.size() > COLUMNS; --i)
			{
				if (!owned.test(items.get(i)))
				{
					items.remove(i);
					itemTiers.remove(i);
				}
			}

			for (int col = 0; col < items.size() && col < COLUMNS; ++col)
			{
				layout[row * COLUMNS + col] = items.get(col);
				tierAt[row * COLUMNS + col] = itemTiers.get(col);
			}
		}

		return new Ladder(layout, tierAt);
	}

	/**
	 * Picks an upgrade for each slot the player doesn't have the best item in.
	 *
	 * @param wanted whether an item is worth suggesting (wearable, within budget)
	 * @param best suggest the best wanted item above the player's own, rather than the nearest step up
	 */
	static Map<String, Integer> nextUpgrades(Map<String, int[][]> slots, IntPredicate owned, IntPredicate wanted, boolean best)
	{
		Map<String, Integer> upgrades = new LinkedHashMap<>();
		for (String slot : SLOTS)
		{
			int[][] tiers = slots.get(slot);
			if (tiers == null)
			{
				continue;
			}

			int own = 0;
			while (own < tiers.length && Arrays.stream(tiers[own]).noneMatch(owned))
			{
				++own;
			}

			for (int i = 0; i < own; ++i)
			{
				int t = best ? i : own - 1 - i;
				int pick = Arrays.stream(tiers[t]).filter(wanted).findFirst().orElse(-1);
				if (pick != -1)
				{
					upgrades.put(slot, pick);
					break;
				}
			}
		}
		return upgrades;
	}
}
