package com.lazyloadouts;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;
import lombok.Value;

/**
 * Lays an activity's setups out side by side, each a column of the best gear the player can use, head
 * to toe, with the inventory to bring underneath. The rows are the same slots as the ladder's, so a
 * slot is always found at the same height.
 */
class LoadoutLayout
{
	/**
	 * The first row of the inventory, a blank row below the gear
	 */
	static final int INVENTORY_ROW = LadderLayout.SLOTS.length + 1;

	@Value
	static class Loadout
	{
		/**
		 * Item id at each bank position, -1 where there is none
		 */
		int[] layout;
		/**
		 * The tier of the gear at each position, 1 being the best, and 0 where there is no gear
		 */
		int[] tier;
	}

	/**
	 * @param setups the setups to show, the one the player is looking at first
	 * @param inventory what to bring with that setup, if the wiki says
	 * @param usable whether the player owns an item and can equip it
	 */
	static Loadout build(List<Activity.Loadout> setups, int[] inventory, IntPredicate usable)
	{
		int supplies = inventory == null ? 0 : inventory.length;
		int rows = supplies == 0 ? LadderLayout.SLOTS.length : INVENTORY_ROW + (supplies + LadderLayout.COLUMNS - 1) / LadderLayout.COLUMNS;
		int[] layout = new int[rows * LadderLayout.COLUMNS];
		int[] tierAt = new int[layout.length];
		Arrays.fill(layout, -1);

		for (int col = 0; col < setups.size() && col < LadderLayout.COLUMNS; ++col)
		{
			for (int row = 0; row < LadderLayout.SLOTS.length; ++row)
			{
				int[][] tiers = setups.get(col).getSlots().get(LadderLayout.SLOTS[row]);
				if (tiers == null)
				{
					continue;
				}

				// what the player lacks is shown as the best there is, to say what belongs here
				int pos = row * LadderLayout.COLUMNS + col;
				layout[pos] = tiers[0][0];
				tierAt[pos] = 1;
				int best = best(tiers, usable);
				if (best != -1)
				{
					layout[pos] = Arrays.stream(tiers[best]).filter(usable).findFirst().getAsInt();
					tierAt[pos] = best + 1;
				}
			}
		}

		for (int i = 0; i < supplies; ++i)
		{
			layout[INVENTORY_ROW * LadderLayout.COLUMNS + i] = inventory[i];
		}

		return new Loadout(layout, tierAt);
	}

	/**
	 * @return the index of the best tier with something the player can use, or -1
	 */
	static int best(int[][] tiers, IntPredicate usable)
	{
		for (int t = 0; t < tiers.length; ++t)
		{
			if (Arrays.stream(tiers[t]).anyMatch(usable))
			{
				return t;
			}
		}
		return -1;
	}
}
