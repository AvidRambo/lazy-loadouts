# Lazy Loadouts

Pick a boss or slayer task and get the wiki's recommended gear as a bank tab: what you own, and what
to buy next.

Other gear plugins give you one answer per slot. Lazy Loadouts shows the whole ladder, so you can see
where you stand in each slot and what the next step up is.

## Using it

1. Open your bank.
2. Open the Lazy Loadouts panel in the RuneLite sidebar.
3. Search for a boss or monster and click it, or click **My slayer task**.
4. Pick a setup (Melee, Ranged, Magic, ...) if there is more than one.

A "lazy loadouts" tab opens in your bank. It has two views, switched at the top of the panel. In both,
each equipment slot has its own row, always in this order: head, cape, neck, ammo, weapon, body,
shield, legs, hands, feet, ring, special attack weapon.

### Ladder

One setup, with each row running from the best item on the left to the worst on the right.

- **Bright items** are in your bank and can be withdrawn as usual.
- **Faded items** are ones you don't have. Hover one for its name and Grand Exchange price.
- **Red outlines** mark items you don't have the levels to equip.
- **The side panel** lists the next upgrade for each slot, cheapest first. A green price is one the
  coins in your bank cover. Special attack weapons are listed separately, best first.

### Loadout

Every setup of the activity side by side, one column each, showing the best item you can use in
each slot. The setup you picked is the first column. Where the wiki recommends an inventory for that
setup, it is laid out underneath.

The side panel's **Before you go** section lists what the setup still needs: slots you have nothing
for, gear that is uncharged or broken, and recommended supplies that aren't in your bank.

### Either view

Untick **Show in bank** to get your normal bank back without losing your place in the panel. If you
pick a setup with the bank closed, the tab opens the next time you open the bank. Nothing in your
real bank is moved: the tab is a Bank Tags layout, so the core Bank Tags plugin must be enabled.

### Buying an upgrade

Click an item in the side panel that you don't own and that the Grand Exchange sells. The buy
offer's search shows that item as the only result, for you to click. It doesn't matter whether you
pick the item before or after opening the search, and picking another swaps it. Typing anything
gets the ordinary search back, and the plugin lets go of the search once the offer is placed, or
when you click the item in the panel again.

## The list

- **Star** an activity to keep it under Favorites at the top. Your last five picks are under Recent.
- **Best in slot** shows the share of gear slots, over every setup, in which you own the wiki's top
  pick. Click it for the top picks you lack, the ones that would complete the most setups first.
  It is measured each time you open your bank.
- **Gear changes** appears after a plugin update in which the wiki's top pick for a slot changed.

## Settings

| Setting | Default | What it does |
|---|---|---|
| Show only owned | off | Leaves the items you don't have out of the tab |
| Upgrade budget (gp) | 0 | Suggests the best upgrade per slot within this price. 0 suggests the next step up, whatever it costs |
| Mark gear above your level | on | Outlines items you can't equip yet and leaves them out of upgrade suggestions |

## Where the gear comes from

The ladders are the "Recommended equipment" tables of the
[Old School RuneScape Wiki](https://oldschool.runescape.wiki): 154 pages covering bosses, raids,
minigames and some slayer tasks. The order is the wiki's, not a damage calculation.

Slayer tasks without a table of their own get the wiki's general slayer training setups, with the
weapons a kind of monster calls for moved to the front (demonbane for demons, dragonbane for
dragons, and so on). The panel says when this is the case.

The data is bundled with the plugin, so it makes no network requests. See `NOTICE.md` for sources
and licences.

## Development

```
./gradlew run     # start a RuneLite client with the plugin loaded
./gradlew test
python3 scripts/build_activities.py   # rebuild the bundled data from the wiki
```
