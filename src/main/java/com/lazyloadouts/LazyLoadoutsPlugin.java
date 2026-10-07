package com.lazyloadouts;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.QuantityFormatter;

@PluginDescriptor(
	name = "Lazy Loadouts",
	description = "Pick a boss or slayer task and get the wiki's recommended gear as a bank tab: what you own, and what to buy next",
	tags = {"bank", "gear", "bis", "slayer", "boss", "upgrade", "loadout", "wiki"}
)
@PluginDependency(BankTagsPlugin.class)
public class LazyLoadoutsPlugin extends Plugin
{
	/**
	 * The one tag tab the plugin shows gear in. Picking another setup replaces its contents.
	 */
	static final String TAG = "lazy loadouts";
	/**
	 * The task id the game uses for a boss task, from [proc,helper_slayer_current_assignment]
	 */
	private static final int BOSS_TASK = 98;
	/**
	 * How many of the best items the player lacks to list
	 */
	private static final int MAX_MISSING = 40;
	/**
	 * Names of gear that has to be charged or repaired before it is any use
	 */
	private static final Pattern DRAINED = Pattern.compile(".*(\\((uncharged|empty|inactive|broken|u)\\)| 0)$");

	/**
	 * What the overlay needs to know about the gear in the bank tab, per bank position
	 */
	@Value
	static class Shown
	{
		int[] layout;
		/**
		 * "Neck, tier 2 of 5"
		 */
		String[] labels;
		/**
		 * The levels the player lacks to equip the item ("80 Defence"), null when they can
		 */
		String[] needs;
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private BankTagsService bankTagsService;

	@Inject
	private TagManager tagManager;

	@Inject
	private LayoutManager layoutManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LadderTooltipOverlay tooltipOverlay;

	@Inject
	private ConfigManager configManager;

	@Inject
	private LazyLoadoutsConfig config;

	private List<Activity> activities;
	/**
	 * Every setup once. Activities that fall back on the general setups share them.
	 */
	private Set<Activity.Loadout> setups;
	/**
	 * Item id to the skill levels needed to equip it
	 */
	private Map<Integer, Map<String, Integer>> requirements;
	private Changes changes;
	private LazyLoadoutsPanel panel;
	private NavigationButton navButton;
	private final Map<Integer, Integer> bases = new ConcurrentHashMap<>();

	private volatile Activity activity;
	private volatile Activity.Loadout loadout;
	/**
	 * Show every setup's best gear side by side, rather than one setup's ladder
	 */
	private volatile boolean loadoutView;
	/**
	 * A setup was picked with the bank closed, and is waiting for it to open
	 */
	private boolean waitingForBank;
	/**
	 * Whether the player wants the setup shown as a tab in the bank, or only in the panel
	 */
	private volatile boolean inBank = true;
	// whether the tab was the one open in the bank on the last tick
	private boolean tabWasOpen;
	/**
	 * What the player has in the bank and on them, as of the last look at the bank: base id to the
	 * variants of it they have. A bank placeholder is a reminder of an item, not the item.
	 */
	private Map<Integer, List<Integer>> ownedItems = new HashMap<>();
	/**
	 * Base ids of the items in the bank tab
	 */
	private volatile Set<Integer> tabItems = new HashSet<>();
	@Getter
	private volatile Shown shown;

	@Provides
	LazyLoadoutsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LazyLoadoutsConfig.class);
	}

	@Override
	protected void startUp() throws IOException
	{
		activities = Activity.resolve(load("activities.json", new TypeToken<List<Activity>>()
		{
		}.getType()));
		requirements = load("requirements.json", new TypeToken<Map<Integer, Map<String, Integer>>>()
		{
		}.getType());
		changes = load("changes.json", Changes.class);

		setups = Collections.newSetFromMap(new IdentityHashMap<>());
		activities.forEach(a -> setups.addAll(a.getLoadouts()));

		tagManager.registerTag(TAG, itemId -> tabItems.contains(base(itemId)));

		loadoutView = "true".equals(configManager.getConfiguration(LazyLoadoutsConfig.GROUP, "loadoutView"));
		panel = new LazyLoadoutsPanel(activities, itemManager, configManager, config.onlyOwned(), loadoutView, new LazyLoadoutsPanel.Actions()
		{
			@Override
			public void pick(Activity activity, Activity.Loadout loadout)
			{
				LazyLoadoutsPlugin.this.pick(activity, loadout);
			}

			@Override
			public void close()
			{
				LazyLoadoutsPlugin.this.close();
			}

			@Override
			public void slayerTask()
			{
				clientThread.invokeLater(LazyLoadoutsPlugin.this::pickSlayerTask);
			}

			@Override
			public void onlyOwned(boolean on)
			{
				configManager.setConfiguration(LazyLoadoutsConfig.GROUP, LazyLoadoutsConfig.ONLY_OWNED, on);
			}

			@Override
			public void inBank(boolean on)
			{
				inBank = on;
				clientThread.invokeLater(on ? LazyLoadoutsPlugin.this::open : LazyLoadoutsPlugin.this::hideTab);
			}

			@Override
			public void loadoutView(boolean on)
			{
				loadoutView = on;
				configManager.setConfiguration(LazyLoadoutsConfig.GROUP, "loadoutView", on);
				clientThread.invokeLater(LazyLoadoutsPlugin.this::open);
			}

			@Override
			public void dismissChanges()
			{
				configManager.setConfiguration(LazyLoadoutsConfig.GROUP, "seenChanges", changes.getVersion());
			}
		});
		panel.setLoggedIn(client.getGameState() == GameState.LOGGED_IN);

		navButton = NavigationButton.builder()
			.tooltip("Lazy Loadouts")
			.icon(icon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(tooltipOverlay);

		clientThread.invokeLater(this::announceChanges);
	}

	@Override
	protected void shutDown()
	{
		tagManager.unregisterTag(TAG);
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(tooltipOverlay);
		close();
	}

	/**
	 * Stops showing a setup, and takes its tab out of the bank
	 */
	private void close()
	{
		shown = null;
		loadout = null;
		waitingForBank = false;
		clientThread.invokeLater(this::hideTab);
	}

	/**
	 * Takes the tab out of the bank, leaving the setup picked
	 */
	private void hideTab()
	{
		// so that the plugin closing the tab isn't taken for the player leaving it
		tabWasOpen = false;
		if (TAG.equals(bankTagsService.getActiveTag()))
		{
			bankTagsService.closeBankTag();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		panel.setLoggedIn(event.getGameState() == GameState.LOGGED_IN);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// The player leaving the tab from inside the bank, by picking another tab there, turns "Show in
		// bank" off, so that the panel says what the bank shows. Closing the bank doesn't.
		boolean bankOpen = bankOpen();
		boolean tabOpen = bankOpen && TAG.equals(bankTagsService.getActiveTag());
		if (tabWasOpen && !tabOpen && bankOpen && inBank)
		{
			inBank = false;
			panel.setInBank(false);
		}
		tabWasOpen = tabOpen;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			// the bank's contents arrive in the same tick as its interface
			clientThread.invokeAtTickEnd(() ->
			{
				lookAtBank();
				showProgress();
				if (loadout != null && (waitingForBank || inBank))
				{
					open();
				}
			});
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(LazyLoadoutsConfig.GROUP) && !LazyLoadoutsPanel.STATE_KEYS.contains(event.getKey()))
		{
			panel.setOnlyOwned(config.onlyOwned());
			clientThread.invokeLater(this::open);
		}
	}

	private <T> T load(String resource, Type type) throws IOException
	{
		try (Reader reader = new InputStreamReader(getClass().getResourceAsStream(resource), StandardCharsets.UTF_8))
		{
			return gson.fromJson(reader, type);
		}
	}

	/**
	 * Collapses the variants of an item (charges, ornament kits, placeholders) to one id
	 */
	int base(int itemId)
	{
		return bases.computeIfAbsent(itemId, id -> ItemVariationMapping.map(itemManager.canonicalize(id)));
	}

	private boolean owned(int itemId)
	{
		return ownedItems.containsKey(base(itemId));
	}

	private boolean bankOpen()
	{
		Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
		return bank != null && !bank.isHidden();
	}

	private void pick(Activity activity, Activity.Loadout loadout)
	{
		// a newly opened activity starts out shown in the bank, as the panel's checkbox does
		if (activity != this.activity || this.loadout == null)
		{
			inBank = true;
		}
		this.activity = activity;
		this.loadout = loadout;
		clientThread.invokeLater(this::open);
	}

	private void pickSlayerTask()
	{
		String task = slayerTask();
		if (task == null)
		{
			panel.setHint("You don't have a slayer task.");
			return;
		}

		// the game names some tasks in the singular and the wiki in the plural, or the other way round
		String name = task.toLowerCase();
		Activity match = activities.stream()
			.filter(a ->
			{
				String other = a.getName().toLowerCase();
				return other.equals(name) || other.equals(name + "s") || (other + "s").equals(name);
			})
			.findFirst()
			.orElse(null);
		if (match == null)
		{
			panel.setHint("No setups for your task: " + task + ".");
			return;
		}

		SwingUtilities.invokeLater(() -> panel.select(match));
	}

	@Nullable
	private String slayerTask()
	{
		if (client.getVarpValue(VarPlayerID.SLAYER_COUNT) <= 0)
		{
			return null;
		}

		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		int row;
		if (taskId == BOSS_TASK)
		{
			List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
			if (rows.isEmpty())
			{
				return null;
			}
			row = (Integer) client.getDBTableField(rows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
		}
		else
		{
			List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
			if (rows.isEmpty())
			{
				return null;
			}
			row = rows.get(0);
		}

		return (String) client.getDBTableField(row, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
	}

	/**
	 * Takes stock of what the player has. Only the open bank says what is in it.
	 */
	private void lookAtBank()
	{
		Map<Integer, List<Integer>> owned = new HashMap<>();
		for (int inventoryId : new int[]{InventoryID.BANK, InventoryID.WORN, InventoryID.INV})
		{
			ItemContainer container = client.getItemContainer(inventoryId);
			if (container == null)
			{
				continue;
			}

			for (Item item : container.getItems())
			{
				if (item.getId() > -1 && item.getId() != ItemID.BANK_FILLER
					&& itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId() == -1)
				{
					owned.computeIfAbsent(base(item.getId()), k -> new ArrayList<>()).add(itemManager.canonicalize(item.getId()));
				}
			}
		}
		ownedItems = owned;
	}

	/**
	 * Shows the picked setup in the bank, or waits for the bank when it is closed
	 */
	private void open()
	{
		Activity activity = this.activity;
		Activity.Loadout loadout = this.loadout;
		if (loadout == null)
		{
			return;
		}

		if (!bankOpen())
		{
			waitingForBank = true;
			panel.setLadder(notes(activity), "Upgrades", new ArrayList<>(), "Open your bank to see this setup.", new ArrayList<>());
			panel.setLoadout(notes(activity), "Open your bank to see this setup.");
			return;
		}
		waitingForBank = false;
		lookAtBank();

		int[] layout;
		String[] labels;
		if (loadoutView)
		{
			// the setup being looked at first, then the others in the wiki's order
			List<Activity.Loadout> columns = new ArrayList<>(activity.getLoadouts());
			columns.remove(loadout);
			columns.add(0, loadout);

			LoadoutLayout.Loadout grid = LoadoutLayout.build(columns, loadout.getInventory(), this::usable);
			layout = grid.getLayout();
			labels = new String[layout.length];
			for (int pos = 0; pos < layout.length; ++pos)
			{
				int row = pos / LadderLayout.COLUMNS;
				if (layout[pos] > -1 && row < LadderLayout.SLOTS.length)
				{
					Activity.Loadout column = columns.get(pos % LadderLayout.COLUMNS);
					String slot = LadderLayout.SLOTS[row];
					labels[pos] = column.getName() + ": " + slot + ", tier " + grid.getTier()[pos] + " of " + column.getSlots().get(slot).length;
				}
				else if (layout[pos] > -1)
				{
					labels[pos] = "To bring for " + loadout.getName();
				}
			}
			panel.setLoadout(notes(activity), checks(loadout));
		}
		else
		{
			Map<String, int[][]> slots = loadout.getSlots();
			LadderLayout.Ladder ladder = LadderLayout.build(slots, this::owned, config.onlyOwned());
			layout = ladder.getLayout();
			labels = new String[layout.length];
			for (int pos = 0; pos < layout.length; ++pos)
			{
				if (layout[pos] > -1)
				{
					String slot = LadderLayout.SLOTS[pos / LadderLayout.COLUMNS];
					labels[pos] = capitalize(slot) + ", tier " + ladder.getTier()[pos] + " of " + slots.get(slot).length;
				}
			}
			showUpgrades(activity, slots);
		}

		Set<Integer> items = new HashSet<>();
		String[] needs = new String[layout.length];
		for (int pos = 0; pos < layout.length; ++pos)
		{
			if (layout[pos] > -1)
			{
				items.add(base(layout[pos]));
				needs[pos] = config.markUnwearable() ? needs(layout[pos]) : null;
			}
		}
		tabItems = items;
		shown = new Shown(layout, labels, needs);

		layoutManager.saveLayout(new Layout(TAG, layout));
		if (inBank)
		{
			bankTagsService.openBankTag(TAG, 0);
		}
	}

	private static String notes(Activity activity)
	{
		String notes = "";
		if (activity.getFallback() != null)
		{
			notes += "The wiki has no gear table for this, so these are its general "
				+ activity.getFallback().toLowerCase() + " setups.";
		}
		if (activity.getNote() != null)
		{
			notes += (notes.isEmpty() ? "" : "<br><br>") + activity.getNote();
		}
		return notes;
	}

	/**
	 * @return whether the player has an item and the levels to equip it
	 */
	private boolean usable(int itemId)
	{
		return owned(itemId) && (!config.markUnwearable() || needs(itemId) == null);
	}

	/**
	 * @return what the player is missing, or has to see to, before leaving with a setup, as HTML
	 */
	private String checks(Activity.Loadout loadout)
	{
		List<String> lines = new ArrayList<>();
		for (String slot : LadderLayout.SLOTS)
		{
			int[][] tiers = loadout.getSlots().get(slot);
			if (tiers == null || slot.equals(LadderLayout.SPECIAL))
			{
				continue;
			}

			int best = LoadoutLayout.best(tiers, this::usable);
			if (best == -1)
			{
				lines.add("Nothing for your " + slot + " slot.");
				continue;
			}

			for (int itemId : tiers[best])
			{
				// drained only if every one of them the player has is
				List<Integer> variants = ownedItems.get(base(itemId));
				if (usable(itemId) && variants != null && variants.stream().allMatch(v -> DRAINED.matcher(name(v)).matches()))
				{
					lines.add(name(variants.get(0)) + " needs charging or repairing.");
				}
			}
		}

		if (loadout.getInventory() != null)
		{
			List<String> lacking = new ArrayList<>();
			for (int itemId : loadout.getInventory())
			{
				if (!owned(itemId))
				{
					lacking.add(name(itemId));
				}
			}
			if (!lacking.isEmpty())
			{
				lines.add("Not in your bank: " + String.join(", ", lacking) + ".");
			}
		}

		return lines.isEmpty() ? "You have everything for this setup." : String.join("<br>", lines);
	}

	private void showUpgrades(Activity activity, Map<String, int[][]> slots)
	{
		int budget = config.upgradeBudget();
		IntPredicate wanted = itemId ->
		{
			if (config.markUnwearable() && needs(itemId) != null)
			{
				return false;
			}
			long price = itemManager.getItemPrice(itemId);
			return budget <= 0 || (price > 0 && price <= budget);
		};

		long coins = coins();
		List<LazyLoadoutsPanel.Gear> upgrades = new ArrayList<>();
		LadderLayout.nextUpgrades(slots, this::owned, wanted, budget > 0).forEach((slot, itemId) ->
		{
			// special attack weapons are extras with a list of their own, not a slot to fill
			if (!slot.equals(LadderLayout.SPECIAL))
			{
				upgrades.add(gear(capitalize(slot), itemId, coins, false));
			}
		});
		// cheapest first, with what can't be bought last
		upgrades.sort(Comparator.comparingLong((LazyLoadoutsPanel.Gear u) -> u.getPrice() > 0 ? u.getPrice() : Long.MAX_VALUE));

		List<LazyLoadoutsPanel.Gear> specials = new ArrayList<>();
		int[][] specialTiers = slots.getOrDefault(LadderLayout.SPECIAL, new int[0][]);
		for (int tier = 0; tier < specialTiers.length; ++tier)
		{
			for (int itemId : specialTiers[tier])
			{
				String lacking = config.markUnwearable() ? needs(itemId) : null;
				specials.add(gear("Tier " + (tier + 1) + (lacking != null ? ", needs " + lacking : ""), itemId, coins, owned(itemId)));
			}
		}

		String within = QuantityFormatter.quantityToStackSize(budget);
		panel.setLadder(notes(activity),
			budget > 0 ? "Best upgrades within " + within : "Next upgrade per slot",
			upgrades,
			budget > 0 ? "No upgrades within " + within + "." : "Nothing to upgrade: you have the best the wiki lists in every slot you can wear.",
			specials);
	}

	/**
	 * Works out how much of the best gear the player has, over every setup there is
	 */
	private void showProgress()
	{
		Progress progress = Progress.of(setups, this::owned);
		long coins = coins();
		List<LazyLoadoutsPanel.Gear> missing = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : progress.getMissing().entrySet())
		{
			if (missing.size() == MAX_MISSING)
			{
				break;
			}
			missing.add(gear("Best in " + e.getValue() + (e.getValue() == 1 ? " setup" : " setups"), e.getKey(), coins, false));
		}

		int share = progress.getSlots() == 0 ? 0 : 100 * progress.getBest() / progress.getSlots();
		panel.setProgress(share + "%",
			"You own the wiki's top pick in " + QuantityFormatter.formatNumber(progress.getBest()) + " of "
				+ QuantityFormatter.formatNumber(progress.getSlots()) + " gear slots, over every setup."
				+ (missing.isEmpty() ? "" : "<br><br>These are the top picks you lack, the ones that would complete the most setups first."),
			missing);
	}

	/**
	 * Tells the player what an update changed, once
	 */
	private boolean announceChanges()
	{
		// item names aren't there to be read until the game's cache is
		if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState())
		{
			return false;
		}

		String seen = configManager.getConfiguration(LazyLoadoutsConfig.GROUP, "seenChanges");
		if (seen == null)
		{
			// a new install has nothing to compare with
			configManager.setConfiguration(LazyLoadoutsConfig.GROUP, "seenChanges", changes.getVersion());
		}
		else if (!seen.equals(changes.getVersion()))
		{
			List<LazyLoadoutsPanel.Gear> items = new ArrayList<>();
			for (Changes.Change change : changes.getChanges())
			{
				items.add(gear(change.getActivity() + ": " + change.getLoadout() + ", " + change.getSlot(), change.getItem(), 0, false));
			}
			panel.setChanges(items);
		}
		return true;
	}

	private LazyLoadoutsPanel.Gear gear(String caption, int itemId, long coins, boolean owned)
	{
		long price = itemManager.getItemPrice(itemId);
		return new LazyLoadoutsPanel.Gear(caption, itemId, name(itemId), price, price > 0 && price <= coins, owned);
	}

	private String name(int itemId)
	{
		return itemManager.getItemComposition(itemId).getName();
	}

	/**
	 * @return the coins the player has in the bank and inventory, platinum tokens included
	 */
	private long coins()
	{
		long coins = 0;
		for (int inventoryId : new int[]{InventoryID.BANK, InventoryID.INV})
		{
			ItemContainer container = client.getItemContainer(inventoryId);
			if (container != null)
			{
				coins += container.count(ItemID.COINS) + 1000L * container.count(ItemID.PLATINUM);
			}
		}
		return coins;
	}

	/**
	 * @return the levels the player lacks to equip an item, as "70 Attack, 70 Defence", or null
	 */
	@Nullable
	private String needs(int itemId)
	{
		Map<String, Integer> skills = requirements.get(itemId);
		if (skills == null)
		{
			return null;
		}

		List<String> lacking = new ArrayList<>();
		for (Map.Entry<String, Integer> e : skills.entrySet())
		{
			Skill skill;
			try
			{
				skill = Skill.valueOf(e.getKey().toUpperCase());
			}
			catch (IllegalArgumentException ex)
			{
				// not a skill, such as a combat level
				continue;
			}

			if (client.getRealSkillLevel(skill) < e.getValue())
			{
				lacking.add(e.getValue() + " " + skill.getName());
			}
		}
		return lacking.isEmpty() ? null : String.join(", ", lacking);
	}

	private static String capitalize(String s)
	{
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static BufferedImage icon()
	{
		// three rows of bank slots, the leftmost of each lit up
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		for (int row = 0; row < 3; ++row)
		{
			for (int col = 0; col < 3; ++col)
			{
				g.setColor(col == 0 ? new Color(0xff, 0x98, 0x1f) : new Color(0x80, 0x80, 0x80));
				g.fillRect(1 + col * 5, 1 + row * 5, 4, 4);
			}
		}
		g.dispose();
		return image;
	}
}
