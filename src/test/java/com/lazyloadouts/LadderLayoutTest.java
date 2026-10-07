package com.lazyloadouts;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LadderLayoutTest
{
	private static final int COLUMNS = LadderLayout.COLUMNS;

	private static int[] row(int[] cells, int row)
	{
		return Arrays.copyOfRange(cells, row * COLUMNS, (row + 1) * COLUMNS);
	}

	private static <T> T load(String resource, Type type) throws Exception
	{
		try (Reader reader = new InputStreamReader(Activity.class.getResourceAsStream(resource), StandardCharsets.UTF_8))
		{
			return new Gson().fromJson(reader, type);
		}
	}

	@Test
	public void givesEachSlotItsOwnRowBestFirst()
	{
		Map<String, int[][]> slots = new HashMap<>();
		slots.put("head", new int[][]{{1}, {2, 3}, {4}});
		slots.put("neck", new int[][]{{5}});

		LadderLayout.Ladder ladder = LadderLayout.build(slots, itemId -> false, false);

		assertEquals(LadderLayout.SLOTS.length * COLUMNS, ladder.getLayout().length);
		assertArrayEquals(new int[]{1, 2, 3, 4, -1, -1, -1, -1}, row(ladder.getLayout(), 0));
		assertArrayEquals(new int[]{1, 2, 2, 3, 0, 0, 0, 0}, row(ladder.getTier(), 0));
		// cape has nothing, but still keeps its row
		assertArrayEquals(new int[]{-1, -1, -1, -1, -1, -1, -1, -1}, row(ladder.getLayout(), 1));
		assertArrayEquals(new int[]{5, -1, -1, -1, -1, -1, -1, -1}, row(ladder.getLayout(), 2));
	}

	@Test
	public void showsOnlyOwnedMembersOfALargeFamily()
	{
		Map<String, int[][]> slots = new HashMap<>();
		slots.put("head", new int[][]{{1}, {10, 11, 12, 13, 14}, {2}});

		LadderLayout.Ladder ladder = LadderLayout.build(slots, itemId -> itemId == 12 || itemId == 13, false);
		assertArrayEquals(new int[]{1, 12, 13, 2, -1, -1, -1, -1}, row(ladder.getLayout(), 0));
		assertArrayEquals(new int[]{1, 2, 2, 3, 0, 0, 0, 0}, row(ladder.getTier(), 0));
		// with none owned, one member stands for the family
		assertArrayEquals(new int[]{1, 10, 2, -1, -1, -1, -1, -1},
			row(LadderLayout.build(slots, itemId -> false, false).getLayout(), 0));
	}

	@Test
	public void keepsOwnedItemsWhenARowIsTooLong()
	{
		Map<String, int[][]> slots = new HashMap<>();
		slots.put("head", new int[][]{{1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}});

		LadderLayout.Ladder ladder = LadderLayout.build(slots, itemId -> itemId == 10, false);
		assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 10}, row(ladder.getLayout(), 0));
		assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6, 7, 10}, row(ladder.getTier(), 0));
	}

	@Test
	public void canLeaveOutWhatThePlayerDoesNotOwn()
	{
		Map<String, int[][]> slots = new HashMap<>();
		slots.put("head", new int[][]{{1}, {2, 3}, {4}});

		LadderLayout.Ladder ladder = LadderLayout.build(slots, itemId -> itemId >= 3, true);
		assertArrayEquals(new int[]{3, 4, -1, -1, -1, -1, -1, -1}, row(ladder.getLayout(), 0));
		assertArrayEquals(new int[]{2, 3, 0, 0, 0, 0, 0, 0}, row(ladder.getTier(), 0));
	}

	@Test
	public void suggestsTheNextStepUp()
	{
		Map<String, int[][]> slots = new HashMap<>();
		slots.put("head", new int[][]{{1}, {2, 3}, {4}});
		slots.put("neck", new int[][]{{5}, {6}});
		slots.put("ring", new int[][]{{7}, {8}});

		// owns the worst helm, the best amulet and no ring
		Map<String, Integer> upgrades = LadderLayout.nextUpgrades(slots, itemId -> itemId == 4 || itemId == 5,
			itemId -> true, false);

		assertEquals(2, upgrades.size());
		assertEquals(Integer.valueOf(2), upgrades.get("head"));
		assertEquals(Integer.valueOf(8), upgrades.get("ring"));
	}

	@Test
	public void skipsUpgradesThatAreNotWanted()
	{
		Map<String, int[][]> slots = new HashMap<>();
		slots.put("head", new int[][]{{1}, {2, 3}, {4}, {5}});

		// the nearest step up the player can use, when the one right above is out of reach
		assertEquals(Integer.valueOf(1),
			LadderLayout.nextUpgrades(slots, itemId -> itemId == 4, itemId -> itemId == 1, false).get("head"));
		assertEquals(Integer.valueOf(3),
			LadderLayout.nextUpgrades(slots, itemId -> itemId == 5, itemId -> itemId == 3 || itemId == 4, true).get("head"));
		assertTrue(LadderLayout.nextUpgrades(slots, itemId -> itemId == 4, itemId -> false, false).isEmpty());
	}

	private static Activity.Loadout setup(String name, int[][] head, int[][] neck)
	{
		Map<String, int[][]> slots = new HashMap<>();
		slots.put("head", head);
		if (neck != null)
		{
			slots.put("neck", neck);
		}
		Activity.Loadout setup = new Activity.Loadout();
		setup.setName(name);
		setup.setSlots(slots);
		return setup;
	}

	@Test
	public void laysSetupsOutAsColumnsOfTheBestThePlayerCanUse()
	{
		List<Activity.Loadout> setups = Arrays.asList(
			setup("Melee", new int[][]{{1}, {2}, {3}}, new int[][]{{5}, {6}}),
			setup("Ranged", new int[][]{{7}, {8}}, null));

		// has the second best melee helm, the worst amulet, and nothing for ranged
		LoadoutLayout.Loadout loadout = LoadoutLayout.build(setups, new int[]{20, 21}, itemId -> itemId == 2 || itemId == 3 || itemId == 6);

		assertArrayEquals(new int[]{2, 7, -1, -1, -1, -1, -1, -1}, row(loadout.getLayout(), 0));
		assertArrayEquals(new int[]{2, 1, 0, 0, 0, 0, 0, 0}, row(loadout.getTier(), 0));
		assertArrayEquals(new int[]{6, -1, -1, -1, -1, -1, -1, -1}, row(loadout.getLayout(), 2));
		// a blank row, then what to bring
		assertArrayEquals(new int[]{-1, -1, -1, -1, -1, -1, -1, -1}, row(loadout.getLayout(), LoadoutLayout.INVENTORY_ROW - 1));
		assertArrayEquals(new int[]{20, 21, -1, -1, -1, -1, -1, -1}, row(loadout.getLayout(), LoadoutLayout.INVENTORY_ROW));
		assertEquals((LoadoutLayout.INVENTORY_ROW + 1) * COLUMNS, loadout.getLayout().length);
	}

	@Test
	public void countsTheSlotsThePlayerHasTheBestIn()
	{
		List<Activity.Loadout> setups = Arrays.asList(
			setup("Melee", new int[][]{{1}, {2}}, new int[][]{{5, 9}, {6}}),
			setup("Ranged", new int[][]{{7}, {8}}, new int[][]{{4}}),
			setup("Magic", new int[][]{{7}}, null));

		// the second of two equal amulets counts as having the best
		Progress progress = Progress.of(setups, itemId -> itemId == 1 || itemId == 9);

		assertEquals(5, progress.getSlots());
		assertEquals(2, progress.getBest());
		// the helm two setups want comes before the amulet one does
		assertEquals(Arrays.asList(7, 4), new java.util.ArrayList<>(progress.getMissing().keySet()));
		assertEquals(Integer.valueOf(2), progress.getMissing().get(7));
	}

	@Test
	public void bundledActivitiesLoad() throws Exception
	{
		List<Activity> activities = Activity.resolve(load("activities.json", new TypeToken<List<Activity>>()
		{
		}.getType()));
		Map<Integer, Map<String, Integer>> requirements = load("requirements.json", new TypeToken<Map<Integer, Map<String, Integer>>>()
		{
		}.getType());

		Changes changes = load("changes.json", Changes.class);

		assertFalse(activities.isEmpty());
		assertFalse(requirements.isEmpty());
		assertFalse(changes.getVersion().isEmpty());
		Set<String> known = new HashSet<>(Arrays.asList(LadderLayout.SLOTS));
		for (Activity activity : activities)
		{
			assertFalse(activity.getName(), activity.getLoadouts().isEmpty());
			for (Activity.Loadout loadout : activity.getLoadouts())
			{
				assertFalse(activity.getName(), loadout.getName().isEmpty());
				assertTrue(activity.getName(), known.containsAll(loadout.getSlots().keySet()));
				LadderLayout.build(loadout.getSlots(), itemId -> false, false);
				LoadoutLayout.build(activity.getLoadouts(), loadout.getInventory(), itemId -> false);
			}
		}
	}
}
