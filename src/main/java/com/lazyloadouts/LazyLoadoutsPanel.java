package com.lazyloadouts;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.Value;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.QuantityFormatter;

/**
 * Two views that take turns: the list to find an activity in, and the activity being looked at
 */
class LazyLoadoutsPanel extends PluginPanel
{
	/**
	 * Panel state kept between sessions, next to the plugin's settings but not among them
	 */
	static final List<String> STATE_KEYS = Arrays.asList("recent", "favorites", "showNotes", "showChecks", "showUpgrades",
		"showSpecials", "loadoutView", "seenChanges");

	/**
	 * What the panel asks of the plugin
	 */
	interface Actions
	{
		void pick(Activity activity, Activity.Loadout loadout);

		/**
		 * The player went back to the list
		 */
		void close();

		void slayerTask();

		void onlyOwned(boolean on);

		void inBank(boolean on);

		/**
		 * @param on show every setup's best gear side by side, rather than one setup's ladder
		 */
		void loadoutView(boolean on);

		void dismissChanges();
	}


	private static final int MAX_RECENT = 5;
	private static final String NAME_SEPARATOR = ";";
	private static final Icon STAR_ON = star(true);
	private static final Icon STAR_OFF = star(false);
	// a label only wraps its text when told how wide it is
	private static final String WRAP = "<html><body style='width: " + (PANEL_WIDTH - 70) + "px'>";

	/**
	 * A piece of gear listed in the panel
	 */
	@Value
	static class Gear
	{
		/**
		 * What the item is in this list: its slot, or its tier
		 */
		String caption;
		int itemId;
		String name;
		/**
		 * Grand Exchange price, 0 when the item isn't sold there
		 */
		long price;
		/**
		 * Whether the coins the player has in the bank cover it
		 */
		boolean affordable;
		boolean owned;
	}

	private final List<Activity> activities;
	private final ItemManager itemManager;
	private final ConfigManager configManager;
	private final Actions actions;

	private final JPanel finder = column();
	private final IconTextField search = new IconTextField();
	private final JButton slayerTask = new JButton("My slayer task");
	private final JLabel hint = new JLabel();
	private final JPanel changesRow = new JPanel(new BorderLayout(6, 0));
	private final JLabel changesText = new JLabel();
	private final JPanel progressRow = new JPanel(new BorderLayout(6, 0));
	private final JLabel progressText = new JLabel("Best in slot");
	private final JLabel progressValue = new JLabel();
	private final JLabel favoritesHeading = heading("Favorites");
	private final JPanel favoriteRows = column();
	private final JPanel recentHeading = new JPanel(new BorderLayout());
	private final JPanel recentRows = column();
	private final JLabel allHeading = heading("All");
	private final Map<Activity, ActivityRow> rows = new LinkedHashMap<>();
	private final JLabel nothing = new JLabel();

	private final JPanel viewer = column();
	private final JLabel title = new JLabel();
	private final JComboBox<String> setups = new JComboBox<>();
	private final JToggleButton ladderView = new JToggleButton("Ladder");
	private final JToggleButton loadoutView = new JToggleButton("Loadout");
	private final JCheckBox inBank = new JCheckBox("Show in bank", true);
	private final JCheckBox onlyOwned = new JCheckBox("Only what I own");
	private final JLabel notes = new JLabel();
	private final JPanel notesSection;
	private final JLabel upgradesHeading = new JLabel();
	private final JLabel noUpgrades = new JLabel();
	private final JPanel noUpgradesBox = boxed(noUpgrades);
	private final GearList upgrades = new GearList();
	private final GearList specials = new GearList();
	private final JPanel specialsSection;
	private final JPanel upgradesSection;
	private final JLabel checks = new JLabel();
	private final JPanel checksSection;

	// a list of gear with a line about it: the missing best-in-slot items, or what an update changed
	private final JPanel lister = column();
	private final JLabel listTitle = new JLabel();
	private final JLabel listSummary = new JLabel();
	private final GearList listItems = new GearList();
	private String progressSummary = "";
	private List<Gear> progressItems = new ArrayList<>();
	private List<Gear> changeItems = new ArrayList<>();

	private Activity activity;
	private boolean hasSpecials;
	// set while the setups are being filled in, so that doing it doesn't count as the player picking one
	private boolean filling;

	LazyLoadoutsPanel(List<Activity> activities, ItemManager itemManager, ConfigManager configManager, boolean onlyOwnedOn,
		boolean loadoutViewOn, Actions actions)
	{
		this.activities = activities.stream()
			.sorted(Comparator.comparing(Activity::getName, String.CASE_INSENSITIVE_ORDER))
			.collect(Collectors.toList());
		this.itemManager = itemManager;
		this.configManager = configManager;
		this.actions = actions;

		// Both views stay in the panel and take turns being visible, and the rows of the list are made
		// once and hidden when they don't match. Taking components out of a window is slow enough on
		// macOS that swapping a few hundred of them froze the panel for most of a second.
		JPanel views = column();
		stack(views, finder, 0);
		stack(views, viewer, 0);
		stack(views, lister, 0);
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));
		// at the top, so that a short view isn't stretched down the panel
		add(views, BorderLayout.NORTH);

		// Finding an activity
		search.setIcon(IconTextField.Icon.SEARCH);
		search.setPreferredSize(new Dimension(0, 30));
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		search.addClearListener(this::filter);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				filter();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				filter();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				filter();
			}
		});

		slayerTask.setToolTipText("Open the setups for the slayer task you have now");
		slayerTask.addActionListener(e -> actions.slayerTask());

		hint.setForeground(ColorScheme.BRAND_ORANGE);
		hint.setVisible(false);

		stack(finder, search, 0);
		stack(finder, slayerTask, 6);
		nothing.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		stack(finder, hint, 6);
		recentHeading.add(heading("Recent"), BorderLayout.CENTER);
		recentHeading.add(link("Clear", "Empty the recent list", () -> saveRecent(new ArrayList<>())), BorderLayout.EAST);

		// What an update changed, and how much of the best gear the player has
		changesText.setForeground(ColorScheme.BRAND_ORANGE);
		changesRow.add(changesText, BorderLayout.CENTER);
		changesRow.add(link("x", "Dismiss", () ->
		{
			changesRow.setVisible(false);
			actions.dismissChanges();
		}), BorderLayout.EAST);
		clickable(changesRow, () -> showList("Gear changes", "The best item for these slots changed in the last update.", changeItems));
		changesRow.setVisible(false);

		progressText.setForeground(ColorScheme.TEXT_COLOR);
		progressValue.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		progressValue.setFont(FontManager.getRunescapeSmallFont());
		progressRow.setToolTipText("How many gear slots, over every setup, you own the wiki's top pick for");
		progressRow.add(progressText, BorderLayout.CENTER);
		progressRow.add(progressValue, BorderLayout.EAST);
		clickable(progressRow, () -> showList("Best in slot", progressSummary, progressItems));
		setProgress(null, "Open your bank once, and this will show how much of the best gear you own.", new ArrayList<>());

		stack(finder, changesRow, 6);
		stack(finder, progressRow, 6);
		stack(finder, favoritesHeading, 8);
		stack(finder, favoriteRows, 0);
		stack(finder, recentHeading, 8);
		stack(finder, recentRows, 0);
		stack(finder, allHeading, 8);
		stack(finder, nothing, 8);
		for (Activity activity : this.activities)
		{
			ActivityRow row = new ActivityRow(activity, false);
			rows.put(activity, row);
			stack(finder, row, 2);
		}

		// Looking at one
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);

		JButton back = new JButton("Back");
		back.setToolTipText("Back to the list. Closes the tab in the bank.");
		back.addActionListener(e ->
		{
			actions.close();
			showFinder();
		});

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.add(title, BorderLayout.CENTER);
		header.add(back, BorderLayout.EAST);

		setups.setToolTipText("The wiki's setups for this");
		setups.addActionListener(e ->
		{
			if (!filling && activity != null && setups.getSelectedIndex() >= 0)
			{
				actions.pick(activity, activity.getLoadouts().get(setups.getSelectedIndex()));
			}
		});

		inBank.setToolTipText("Show this setup as a tab in your bank. Turn it off to use the bank as usual without losing your place here.");
		inBank.addActionListener(e -> actions.inBank(inBank.isSelected()));

		ladderView.setToolTipText("One setup, every tier: what you own and what to buy next");
		loadoutView.setToolTipText("Every setup side by side, the best you own in each slot, with what to bring");
		ButtonGroup viewGroup = new ButtonGroup();
		viewGroup.add(ladderView);
		viewGroup.add(loadoutView);
		(loadoutViewOn ? loadoutView : ladderView).setSelected(true);
		ladderView.addActionListener(e -> showView());
		loadoutView.addActionListener(e -> showView());
		JPanel viewToggle = new JPanel(new GridLayout(1, 2, 4, 0));
		viewToggle.add(ladderView);
		viewToggle.add(loadoutView);

		onlyOwned.setToolTipText("Leave the items you don't have out of the bank tab");
		onlyOwned.setSelected(onlyOwnedOn);
		onlyOwned.addActionListener(e -> actions.onlyOwned(onlyOwned.isSelected()));

		notesSection = section(new JLabel("Notes"), "showNotes", boxed(notes));
		specialsSection = section(new JLabel("Special attack weapons, best first"), "showSpecials", specials);

		checksSection = section(new JLabel("Before you go"), "showChecks", boxed(checks));

		stack(viewer, header, 0);
		stack(viewer, viewToggle, 8);
		stack(viewer, setups, 4);
		stack(viewer, inBank, 4);
		stack(viewer, onlyOwned, 0);
		stack(viewer, notesSection, 10);
		stack(viewer, checksSection, 10);
		noUpgrades.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel upgradesBody = column();
		stack(upgradesBody, noUpgradesBox, 0);
		stack(upgradesBody, upgrades, 0);
		upgradesSection = section(upgradesHeading, "showUpgrades", upgradesBody);
		stack(viewer, upgradesSection, 10);
		stack(viewer, specialsSection, 10);

		// The list of gear
		listTitle.setFont(FontManager.getRunescapeBoldFont());
		listTitle.setForeground(ColorScheme.BRAND_ORANGE);
		JButton listBack = new JButton("Back");
		listBack.addActionListener(e -> showFinder());
		JPanel listHeader = new JPanel(new BorderLayout(6, 0));
		listHeader.add(listTitle, BorderLayout.CENTER);
		listHeader.add(listBack, BorderLayout.EAST);
		listSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		stack(lister, listHeader, 0);
		stack(lister, boxed(listSummary), 8);
		stack(lister, listItems, 8);

		showFinder();
	}

	/**
	 * Shows how much of the best gear the player has.
	 *
	 * @param share "61%", or null when it isn't known yet
	 * @param items the top picks the player lacks
	 */
	void setProgress(String share, String summary, List<Gear> items)
	{
		SwingUtilities.invokeLater(() ->
		{
			progressValue.setText(share == null ? "" : share);
			progressSummary = summary;
			progressItems = items;
		});
	}

	/**
	 * Tells the player that an update changed the best item for some slots
	 */
	void setChanges(List<Gear> items)
	{
		SwingUtilities.invokeLater(() ->
		{
			changeItems = items;
			changesText.setText(items.size() + (items.size() == 1 ? " gear change" : " gear changes") + " in this update");
			changesRow.setVisible(!items.isEmpty());
		});
	}

	/**
	 * Says something next to the list, when there is no activity to say it under
	 */
	void setHint(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			hint.setText(WRAP + text);
			hint.setVisible(true);
		});
	}

	void setOnlyOwned(boolean on)
	{
		SwingUtilities.invokeLater(() -> onlyOwned.setSelected(on));
	}

	void setInBank(boolean on)
	{
		SwingUtilities.invokeLater(() -> inBank.setSelected(on));
	}

	/**
	 * The slayer task can only be read from the game once the player is in it
	 */
	void setLoggedIn(boolean loggedIn)
	{
		SwingUtilities.invokeLater(() -> slayerTask.setVisible(loggedIn));
	}

	/**
	 * Fills in the ladder view of the setup that was picked.
	 *
	 * @param notes what to know about the monster, as HTML, or empty
	 * @param heading what the upgrades are ("Next upgrade per slot")
	 * @param message shown in place of the upgrades when there are none
	 * @param specialItems the setup's special attack weapons, which are extras and not a slot to fill
	 */
	void setLadder(String notes, String heading, List<Gear> items, String message, List<Gear> specialItems)
	{
		SwingUtilities.invokeLater(() ->
		{
			setNotes(notes);
			upgradesHeading.setText(heading);
			noUpgrades.setText(WRAP + message);
			noUpgradesBox.setVisible(items.isEmpty());
			upgrades.show(items);
			specials.show(specialItems);
			hasSpecials = !specialItems.isEmpty();
			specialsSection.setVisible(!loadoutView.isSelected() && hasSpecials);

			viewer.revalidate();
			viewer.repaint();
		});
	}

	/**
	 * Fills in the loadout view of the setup that was picked.
	 *
	 * @param lines what is missing or not ready for the trip, as HTML
	 */
	void setLoadout(String notes, String lines)
	{
		SwingUtilities.invokeLater(() ->
		{
			setNotes(notes);
			checks.setText(WRAP + lines);

			viewer.revalidate();
			viewer.repaint();
		});
	}

	private void setNotes(String text)
	{
		notes.setText(WRAP + text);
		notesSection.setVisible(!text.isEmpty());
	}

	/**
	 * Shows the controls and sections of the view the player chose, and has the bank tab follow
	 */
	private void showView()
	{
		showSections();
		actions.loadoutView(loadoutView.isSelected());
	}

	private void showSections()
	{
		boolean loadout = loadoutView.isSelected();
		onlyOwned.setVisible(!loadout);
		checksSection.setVisible(loadout);
		upgradesSection.setVisible(!loadout);
		specialsSection.setVisible(!loadout && hasSpecials);
	}

	private void showList(String title, String summary, List<Gear> items)
	{
		listTitle.setText(title);
		listSummary.setText(WRAP + summary);
		listItems.show(items);
		show(lister);
	}

	/**
	 * Shows an activity's setups in place of the list, and opens the first of them
	 */
	void select(Activity activity)
	{
		this.activity = activity;

		title.setText(WRAP + activity.getName());
		filling = true;
		setups.removeAllItems();
		for (Activity.Loadout loadout : activity.getLoadouts())
		{
			setups.addItem(loadout.getName());
		}
		filling = false;
		setups.setVisible(activity.getLoadouts().size() > 1);
		inBank.setSelected(true);
		setLadder("", "", new ArrayList<>(), "", new ArrayList<>());
		setLoadout("", "");
		showSections();

		show(viewer);
		actions.pick(activity, activity.getLoadouts().get(0));

		List<String> recent = names("recent");
		recent.remove(activity.getName());
		recent.add(0, activity.getName());
		saveRecent(recent.subList(0, Math.min(MAX_RECENT, recent.size())));
	}

	private void showFinder()
	{
		activity = null;
		hint.setVisible(false);
		showPinned();
		show(finder);
	}

	/**
	 * Fills in the favorites and the recent picks. There are only ever a handful of these rows, so
	 * unlike the full list they are cheap to replace.
	 */
	private void showPinned()
	{
		List<String> favorites = names("favorites");
		List<String> recent = names("recent");
		recent.removeAll(favorites);

		favoriteRows.removeAll();
		recentRows.removeAll();
		for (Activity a : activities)
		{
			if (favorites.contains(a.getName()))
			{
				stack(favoriteRows, new ActivityRow(a, false), 2);
			}
		}
		activities.stream()
			.filter(a -> recent.contains(a.getName()))
			.sorted(Comparator.comparingInt(a -> recent.indexOf(a.getName())))
			.forEach(a -> stack(recentRows, new ActivityRow(a, true), 2));
		rows.forEach((a, row) -> row.setFavorite(favorites.contains(a.getName())));

		filter();
	}

	private void show(JPanel view)
	{
		finder.setVisible(view == finder);
		viewer.setVisible(view == viewer);
		lister.setVisible(view == lister);
		revalidate();
		repaint();
	}

	private void filter()
	{
		String query = search.getText().trim().toLowerCase();
		boolean browsing = query.isEmpty();
		favoritesHeading.setVisible(browsing && favoriteRows.getComponentCount() > 0);
		favoriteRows.setVisible(browsing);
		recentHeading.setVisible(browsing && recentRows.getComponentCount() > 0);
		recentRows.setVisible(browsing);
		allHeading.setVisible(browsing);

		boolean any = false;
		for (Map.Entry<Activity, ActivityRow> e : rows.entrySet())
		{
			boolean matches = e.getKey().getName().toLowerCase().contains(query);
			e.getValue().setVisible(matches);
			any |= matches;
		}
		nothing.setText(WRAP + "Nothing matches \"" + search.getText().trim() + "\".");
		nothing.setVisible(!any);

		finder.revalidate();
		finder.repaint();
	}

	/**
	 * A list of activity names kept between sessions
	 */
	private List<String> names(String key)
	{
		String saved = configManager.getConfiguration(LazyLoadoutsConfig.GROUP, key);
		return saved == null || saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(NAME_SEPARATOR)));
	}

	private void saveRecent(List<String> recent)
	{
		configManager.setConfiguration(LazyLoadoutsConfig.GROUP, "recent", String.join(NAME_SEPARATOR, recent));
		if (activity == null)
		{
			showPinned();
		}
	}

	private void toggleFavorite(Activity activity)
	{
		List<String> favorites = names("favorites");
		if (!favorites.remove(activity.getName()))
		{
			favorites.add(activity.getName());
		}
		configManager.setConfiguration(LazyLoadoutsConfig.GROUP, "favorites", String.join(NAME_SEPARATOR, favorites));
		showPinned();
	}

	/**
	 * An activity in the list: click it to open it, click its star to keep it at the top
	 */
	private class ActivityRow extends JPanel
	{
		private final JLabel star = new JLabel(STAR_OFF);

		/**
		 * @param recent whether this is a row of the recent list, which can be taken off it
		 */
		ActivityRow(Activity activity, boolean recent)
		{
			super(new BorderLayout(6, 0));
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setBorder(new EmptyBorder(6, 8, 6, 8));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			JLabel name = new JLabel(activity.getName());
			name.setForeground(ColorScheme.TEXT_COLOR);

			star.setToolTipText("Keep at the top of the list");
			setFavorite(names("favorites").contains(activity.getName()));

			JPanel controls = new JPanel(new BorderLayout(8, 0));
			controls.setOpaque(false);
			controls.add(star, BorderLayout.CENTER);
			if (recent)
			{
				JLabel forget = link("x", "Take off the recent list", () ->
				{
					List<String> names = names("recent");
					names.remove(activity.getName());
					saveRecent(names);
				});
				forget.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				controls.add(forget, BorderLayout.EAST);
			}

			add(name, BorderLayout.CENTER);
			add(controls, BorderLayout.EAST);

			MouseAdapter hover = new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
			};
			addMouseListener(hover);
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					select(activity);
				}
			});
			// the star takes the mouse from the row, so it keeps the row lit itself
			star.addMouseListener(hover);
			star.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					toggleFavorite(activity);
				}
			});
		}

		void setFavorite(boolean favorite)
		{
			star.setIcon(favorite ? STAR_ON : STAR_OFF);
		}
	}

	/**
	 * Makes a row of the list open something when clicked
	 */
	private static void clickable(JPanel row, Runnable onClick)
	{
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(6, 8, 6, 8));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
	}

	/**
	 * A small piece of text that does something when clicked
	 */
	private static JLabel link(String text, String tooltip, Runnable onClick)
	{
		JLabel link = new JLabel(text);
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setForeground(ColorScheme.BRAND_ORANGE);
		link.setToolTipText(tooltip);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
			}
		});
		return link;
	}

	// drawn, as the client's font has no star
	private static Icon star(boolean filled)
	{
		int size = 13;
		Polygon star = new Polygon();
		for (int i = 0; i < 10; ++i)
		{
			double radius = i % 2 == 0 ? 6 : 2.6;
			double angle = Math.PI / 5 * i - Math.PI / 2;
			star.addPoint((int) Math.round(6 + radius * Math.cos(angle)), (int) Math.round(6.5 + radius * Math.sin(angle)));
		}

		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(filled ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR);
		if (filled)
		{
			g.fillPolygon(star);
		}
		g.drawPolygon(star);
		g.dispose();
		return new ImageIcon(image);
	}

	/**
	 * A list of gear whose rows are kept and refilled, for the reason the views are
	 */
	private class GearList extends JPanel
	{
		private final List<GearRow> pool = new ArrayList<>();

		GearList()
		{
			super(new GridBagLayout());
		}

		void show(List<Gear> items)
		{
			while (pool.size() < items.size())
			{
				GearRow row = new GearRow();
				pool.add(row);
				stack(this, row, pool.size() == 1 ? 0 : 2);
			}
			for (int i = 0; i < pool.size(); ++i)
			{
				pool.get(i).setVisible(i < items.size());
				if (i < items.size())
				{
					pool.get(i).show(items.get(i));
				}
			}
		}
	}

	private class GearRow extends JPanel
	{
		private final JLabel icon = new JLabel();
		private final JLabel name = new JLabel();
		private final JLabel caption = new JLabel();
		private final JLabel price = new JLabel();

		GearRow()
		{
			super(new BorderLayout(6, 0));
			icon.setPreferredSize(new Dimension(36, 32));
			name.setForeground(ColorScheme.TEXT_COLOR);
			caption.setFont(FontManager.getRunescapeSmallFont());
			caption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			price.setFont(FontManager.getRunescapeSmallFont());
			price.setHorizontalAlignment(SwingConstants.RIGHT);

			JPanel text = new JPanel(new GridLayout(2, 1));
			text.setOpaque(false);
			text.add(name);
			text.add(caption);

			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setBorder(new EmptyBorder(4, 6, 4, 8));
			add(icon, BorderLayout.WEST);
			add(text, BorderLayout.CENTER);
			add(price, BorderLayout.EAST);
		}

		void show(Gear gear)
		{
			icon.setIcon(null);
			itemManager.getImage(gear.getItemId()).addTo(icon);
			name.setText(gear.getName());
			name.setToolTipText(gear.getName());
			caption.setText(gear.getCaption());
			caption.setToolTipText(gear.getCaption());

			price.setToolTipText(null);
			if (gear.isOwned())
			{
				price.setText("Owned");
				price.setForeground(ColorScheme.BRAND_ORANGE);
			}
			else if (gear.getPrice() <= 0)
			{
				price.setText("Not sold");
				price.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
				price.setToolTipText("Not sold on the Grand Exchange");
			}
			else
			{
				price.setText(QuantityFormatter.quantityToStackSize(gear.getPrice()));
				price.setForeground(gear.isAffordable() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
				price.setToolTipText(gear.isAffordable() ? "The coins in your bank and inventory cover this" : null);
			}
		}
	}

	/**
	 * A heading over a body that the player can fold away, remembering that they did
	 */
	private JPanel section(JLabel heading, String key, JComponent body)
	{
		heading.setFont(FontManager.getRunescapeSmallFont());
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JLabel toggle = new JLabel();
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setForeground(ColorScheme.BRAND_ORANGE);
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		Runnable apply = () ->
		{
			boolean open = !"false".equals(configManager.getConfiguration(LazyLoadoutsConfig.GROUP, key));
			body.setVisible(open);
			toggle.setText(open ? "Hide" : "Show");
		};
		toggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				configManager.setConfiguration(LazyLoadoutsConfig.GROUP, key, !body.isVisible());
				apply.run();
			}
		});
		apply.run();

		JPanel header = new JPanel(new BorderLayout());
		header.add(heading, BorderLayout.CENTER);
		header.add(toggle, BorderLayout.EAST);

		JPanel section = new JPanel(new BorderLayout(0, 4));
		section.add(header, BorderLayout.NORTH);
		section.add(body, BorderLayout.CENTER);
		return section;
	}

	private static JLabel heading(String text)
	{
		JLabel heading = new JLabel(text);
		heading.setFont(FontManager.getRunescapeSmallFont());
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return heading;
	}

	private static JPanel boxed(JComponent content)
	{
		JPanel box = new JPanel(new BorderLayout());
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(new EmptyBorder(6, 8, 6, 8));
		box.add(content, BorderLayout.CENTER);
		return box;
	}

	private static JPanel column()
	{
		return new JPanel(new GridBagLayout());
	}

	/**
	 * Adds a component under the others in a column, as wide as the column
	 */
	private static void stack(JPanel column, Component component, int gapAbove)
	{
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = column.getComponentCount();
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(gapAbove, 0, 0, 0);
		column.add(component, c);
	}
}
