package com.lazyloadouts;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.QuantityFormatter;

/**
 * Explains the items of the ladder shown in the bank: which slot and tier each is, and for the faded
 * ones, which Bank Tags draws as a bare icon with nothing to hover or right click, the name and price.
 * Items the player can't equip yet are outlined.
 */
class LadderTooltipOverlay extends Overlay
{
	private static final Color UNWEARABLE = new Color(200, 40, 40);

	private final Client client;
	private final LazyLoadoutsPlugin plugin;
	private final ItemManager itemManager;
	private final TooltipManager tooltipManager;
	private final BankTagsService bankTagsService;

	@Inject
	LadderTooltipOverlay(Client client, LazyLoadoutsPlugin plugin, ItemManager itemManager, TooltipManager tooltipManager,
		BankTagsService bankTagsService)
	{
		this.client = client;
		this.plugin = plugin;
		this.itemManager = itemManager;
		this.tooltipManager = tooltipManager;
		this.bankTagsService = bankTagsService;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		LazyLoadoutsPlugin.Shown shown = plugin.getShown();
		if (shown == null || !LazyLoadoutsPlugin.TAG.equals(bankTagsService.getActiveTag()))
		{
			return null;
		}

		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.isHidden())
		{
			return null;
		}

		Rectangle view = items.getBounds();
		Point mouse = new Point(client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY());
		boolean hovering = !client.isMenuOpen() && view.contains(mouse);

		for (int pos = 0; pos < shown.getLayout().length; ++pos)
		{
			Widget item = items.getChild(pos);
			if (item == null)
			{
				break;
			}

			// the slot can hold something else if the layout was dragged around
			if (shown.getLayout()[pos] < 0 || item.isHidden() || item.getItemId() < 0
				|| plugin.base(item.getItemId()) != plugin.base(shown.getLayout()[pos]))
			{
				continue;
			}

			Rectangle bounds = item.getBounds();
			if (!view.contains(bounds))
			{
				continue;
			}

			String needs = shown.getNeeds()[pos];
			if (needs != null)
			{
				graphics.setColor(UNWEARABLE);
				graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
			}

			if (hovering && bounds.contains(mouse))
			{
				StringBuilder sb = new StringBuilder();
				// only the faded items need naming: the ones in the bank have the game's own menu
				boolean faded = item.getOpacity() > 0;
				int itemId = itemManager.canonicalize(item.getItemId());
				if (faded)
				{
					sb.append(itemManager.getItemComposition(itemId).getName()).append("</br>");
				}
				sb.append(shown.getLabels()[pos]);
				if (faded)
				{
					long price = itemManager.getItemPrice(itemId);
					sb.append("</br>").append(price > 0 ? "GE: " + QuantityFormatter.quantityToStackSize(price) + " gp" : "No GE price");
				}
				if (needs != null)
				{
					sb.append("</br>").append(ColorUtil.wrapWithColorTag("Needs " + needs, UNWEARABLE));
				}
				tooltipManager.add(new Tooltip(sb.toString()));
			}
		}

		return null;
	}
}
