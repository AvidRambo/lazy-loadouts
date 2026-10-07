#!/usr/bin/env python3
"""Builds activities.json, the gear ladders the plugin ships with, from the OSRS Wiki.

Every wiki page that uses the "Recommended equipment" table becomes an activity, every table on it
a loadout, and every slot a list of tiers (best first), each tier a list of item ids. Slayer tasks
without a table are listed too: they get the general slayer training setups, adjusted for the kind of
monster (demon, dragon, ...) where that calls for particular gear.

Each setup also gets the inventory the wiki recommends with it, where the page makes clear which
inventory goes with which table.

Also builds requirements.json, the skill levels needed to equip each item in those ladders, and
changes.json, the setups whose best item for a slot differs from the previous build. Run it once per
release, so that changes.json compares against the data players have.

    python3 scripts/build_activities.py [cache_dir]

Downloads are kept in cache_dir (default: .cache) so a rerun doesn't hit the wiki again.
"""
import collections
import copy
import datetime
import gzip
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

USER_AGENT = {'User-Agent': 'lazy-loadouts RuneLite plugin data build'}
WIKI_API = 'https://oldschool.runescape.wiki/api.php?'
RESOURCES = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'com', 'lazyloadouts')
# Monster types and weaknesses, as published for the wiki's DPS calculator
MONSTERS = 'https://raw.githubusercontent.com/weirdgloop/osrs-dps-calc/main/cdn/json/monsters.json'
# Equipment skill requirements from Loadout Lab (BSD-2-Clause, see NOTICE.md), pinned to a commit
REQUIREMENTS = ('https://raw.githubusercontent.com/AKAddons/runelite-loadout-lab/a0af2dbb11cfeeb2ea1c6079c744fdac3811b2d7/'
	'src/main/resources/com/loadoutlab/data/equipment_requirements.json.gz')
# Slayer tasks whose wiki page has no table of its own show this activity's setups instead
SLAYER_FALLBACK = 'Slayer training'
SLOTS = ['head', 'cape', 'neck', 'ammo', 'weapon', 'body', 'shield', 'legs', 'hands', 'feet', 'ring', 'special']
GODS = ['Saradomin', 'Guthix', 'Zamorak', 'Armadyl', 'Bandos', 'Ancient']

# Names the wiki uses for a family of interchangeable items
ALIASES = {
	'god blessing': ['Holy blessing', 'Unholy blessing', 'Peaceful blessing', 'War blessing', 'Honourable blessing', 'Ancient blessing'],
	'blessed coif': [g + ' coif' for g in GODS],
	'blessed body': [g + " d'hide body" for g in GODS],
	'blessed chaps': [g + ' chaps' for g in GODS],
	'blessed vambraces': [g + ' bracers' for g in GODS],
	'blessed boots': [g + " d'hide boots" for g in GODS],
	'god capes': [g + ' cape' for g in GODS[:3]],
	'imbued god cape': ['Imbued ' + g.lower() + ' cape' for g in GODS[:3]],
	'mitre': [g + ' mitre' for g in GODS],
	'stole': [g + ' stole' for g in GODS],
	'crozier': [g + ' crozier' for g in GODS],
	'vestment cloak': [g + ' cloak' for g in GODS],
	'vestment robe top': [g + ' robe top' for g in GODS],
	'vestment robe legs': [g + ' robe legs' for g in GODS],
	"ava's device": ["Ava's assembler", "Ava's accumulator", "Ava's attractor"],
	'ring of dueling': ['Ring of dueling(8)'],
}
# The wiki's stand-ins for "whatever you can afford" in an inventory
GENERIC_SUPPLIES = {
	'cheap prayer': 'Prayer potion(4)',
	'expensive prayer': 'Super restore(4)',
	'cheap food': 'Shark',
	'expensive food': 'Anglerfish',
}

# Slayer tasks that are a group of monsters rather than one the monster data knows by name
TASK_TYPES = {'Dragons': ['dragon'], 'Kalphites': ['kalphite'], 'Scabarites': ['kalphite'], 'Vampyres': ['vampyre']}

# What a kind of monster changes about the general setups, per combat style: tiers to put in front of
# a slot's ladder, or (under 'only') to replace it with
TYPE_RULES = {
	'demon': {
		'note': 'Demon: demonbane weapons come first.',
		'melee': {'weapon': [['Emberlight'], ['Arclight'], ['Darklight', 'Silverlight']]},
		'ranged': {'weapon': [['Scorching bow']]},
		'magic': {'weapon': [['Purging staff']]},
	},
	'dragon': {
		'note': 'Dragon: dragonbane weapons come first. Bring dragonfire protection.',
		'melee': {'weapon': [['Dragon hunter lance']], 'shield': [['Dragonfire shield'], ['Anti-dragon shield']]},
		'ranged': {'weapon': [['Dragon hunter crossbow']], 'shield': [['Dragonfire ward'], ['Anti-dragon shield']]},
		'magic': {'weapon': [['Dragon hunter wand']], 'shield': [['Anti-dragon shield']]},
	},
	'kalphite': {
		'note': 'Kalphite: the Keris comes first.',
		'melee': {'weapon': [['Keris partisan of breaching'], ['Keris partisan']]},
	},
	'vampyre': {
		'note': 'Vampyre: the stronger ones can only be hurt by blisterwood or silver weapons.',
		'melee': {'weapon': [['Blisterwood flail'], ['Ivandis flail']]},
	},
	'leafy': {
		'note': 'Only leaf-bladed weapons, broad ammunition and Magic Dart can hurt it.',
		'only': True,
		'melee': {'weapon': [['Leaf-bladed battleaxe'], ['Leaf-bladed sword'], ['Leaf-bladed spear']]},
		'ranged': {'ammo': [['Amethyst broad bolts'], ['Broad bolts'], ['Broad arrows']]},
	},
}

for alias, same_as in {'blessing': 'god blessing', "blessed d'hide body": 'blessed body', 'god cape': 'god capes',
	'blessed bracers': 'blessed vambraces',
	'god capes#imbuing': 'imbued god cape', 'imbued god capes': 'imbued god cape'}.items():
	ALIASES[alias] = ALIASES[same_as]


def fetch_json(url):
	return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=USER_AGENT), timeout=60))


def fetch_gzipped_json(url):
	data = urllib.request.urlopen(urllib.request.Request(url, headers=USER_AGENT), timeout=60).read()
	return json.loads(gzip.decompress(data))


def cached(cache_dir, name, load):
	path = os.path.join(cache_dir, name)
	if not os.path.exists(path):
		with open(path, 'w') as f:
			json.dump(load(), f)
	with open(path) as f:
		return json.load(f)


def fetch_pages():
	titles, cont = [], {}
	while True:
		d = fetch_json(WIKI_API + urllib.parse.urlencode({'action': 'query', 'list': 'embeddedin',
			'eititle': 'Template:Recommended equipment', 'einamespace': 0, 'eilimit': 'max', 'format': 'json', **cont}))
		titles += [p['title'] for p in d['query']['embeddedin']]
		if 'continue' not in d:
			break
		cont = d['continue']

	pages = {}
	for i in range(0, len(titles), 40):
		d = fetch_json(WIKI_API + urllib.parse.urlencode({'action': 'query', 'prop': 'revisions', 'rvprop': 'content',
			'rvslots': 'main', 'titles': '|'.join(titles[i:i + 40]), 'format': 'json', 'formatversion': 2}))
		for p in d['query']['pages']:
			if 'revisions' in p:
				pages[p['title']] = p['revisions'][0]['slots']['main']['content']
		time.sleep(1)
	return pages


def fetch_slayer_tasks():
	d = fetch_json(WIKI_API + urllib.parse.urlencode({'action': 'query', 'list': 'allpages', 'apprefix': 'Slayer task/',
		'apfilterredir': 'nonredirects', 'aplimit': 'max', 'format': 'json'}))
	return [p['title'].split('/', 1)[1] for p in d['query']['allpages']]


def monster_kinds(task, monsters):
	"""The types (demon, dragon, ...) and elemental weakness of a slayer task's monster."""
	if task in TASK_TYPES:
		return TASK_TYPES[task], None
	name = task.lower()
	names = [name, re.sub(r'ies$', 'y', name), re.sub(r'ves$', 'f', name), re.sub(r'es$', '', name), re.sub(r's$', '', name),
		re.sub(r'men$', 'man', name)]
	matches = [m for m in monsters if m['name'].lower() in names]
	if not matches:
		return [], None
	types = {'vampyre' if a.startswith('vampyre') else a for m in matches for a in m.get('attributes') or []}
	weakness = max(matches, key=lambda m: m.get('level') or 0).get('weakness') or {}
	return sorted(types), weakness.get('element')


def adjusted(loadouts, rules, resolve):
	"""A copy of the general setups with a monster type's rules applied."""
	loadouts = copy.deepcopy(loadouts)
	for loadout in loadouts:
		style = loadout['name'].lower().split()[0]
		for rule in rules:
			for slot, tiers in rule.get(style, {}).items():
				front = [ids for ids in ([i for name in tier for i in resolve(name)] for tier in tiers) if ids]
				placed = {i for ids in front for i in ids}
				rest = [] if rule.get('only') else [[i for i in tier if i not in placed] for tier in loadout['slots'].get(slot, [])]
				loadout['slots'][slot] = front + [tier for tier in rest if tier]
	return loadouts


def tables(text, template='recommended equipment'):
	"""Yields (start, wikitext) for each use of a template, matching nested braces."""
	i = 0
	while True:
		i = text.lower().find('{{' + template, i)
		if i < 0:
			return
		depth, j = 0, i
		while j < len(text):
			if text.startswith('{{', j):
				depth += 1
				j += 2
			elif text.startswith('}}', j):
				depth -= 1
				j += 2
				if depth == 0:
					break
			else:
				j += 1
		yield i, text[i:j]
		i = j


def fields(table):
	"""Splits a template call into its named parameters, ignoring pipes inside nested markup."""
	parts, depth, cur, i = [], 0, '', 0
	body = table[2:-2]
	while i < len(body):
		two = body[i:i + 2]
		if two in ('{{', '[['):
			depth += 1
			cur += two
			i += 2
		elif two in ('}}', ']]'):
			depth -= 1
			cur += two
			i += 2
		elif body[i] == '|' and depth == 0:
			parts.append(cur)
			cur = ''
			i += 1
		else:
			cur += body[i]
			i += 1
	parts.append(cur)
	return {k.strip().lower(): v.strip() for k, v in (p.split('=', 1) for p in parts[1:] if '=' in p)}


def item_names(value):
	names = re.findall(r'\{\{\s*[Pp]link[a-z]*\s*\|\s*([^|}]+)', value)
	names += re.findall(r'\[\[([^\]|]+)', re.sub(r'\{\{.*?\}\}', '', value))
	return [n.strip() for n in names if not n.lower().startswith('file:')]


def inventory_names(table):
	"""The distinct items of an Inventory template, in slot order."""
	names = []
	slots = {int(k): v for k, v in fields(table).items() if k.isdigit()}
	for slot in sorted(slots):
		value = slots[slot]
		generic = re.match(r'\{\{\s*([^|}]+)', value)
		name = GENERIC_SUPPLIES.get(generic.group(1).strip().lower()) if generic else value.split('|')[0].strip()
		if name and name not in names:
			names.append(name)
	return names


def paired_inventories(table_starts, inventories):
	"""Which inventory goes with which table, by position, or None where the page leaves it unclear."""
	if not inventories:
		return [None] * len(table_starts)
	if table_starts[-1] < inventories[0][0]:
		# every table, then every inventory: they pair off only if there are as many of each
		return [i[1] for i in inventories] if len(inventories) == len(table_starts) else [None] * len(table_starts)
	paired = []
	for n, start in enumerate(table_starts):
		end = table_starts[n + 1] if n + 1 < len(table_starts) else float('inf')
		between = [names for at, names in inventories if start < at < end]
		paired.append(between[0] if between else None)
	return paired


def tab_name(text, start):
	"""The label of the tabber tab a table sits in, if any."""
	section = text[text.rfind('<tabber>', 0, start):start] if '<tabber>' in text[:start] else ''
	if '</tabber>' in section:
		return None
	labels = re.findall(r'(?:<tabber>|\|-\|)\s*([^=\n|{}\[\]<>]+?)\s*=', section)
	return labels[-1] if labels else None


def main():
	cache_dir = sys.argv[1] if len(sys.argv) > 1 else '.cache'
	os.makedirs(cache_dir, exist_ok=True)
	pages = cached(cache_dir, 'pages.json', fetch_pages)
	names = cached(cache_dir, 'names.json', lambda: fetch_json('https://static.runelite.net/cache/item/names.json'))
	stats = cached(cache_dir, 'stats.json', lambda: fetch_json('https://static.runelite.net/item/stats.ids.min.json'))
	monsters = cached(cache_dir, 'monsters.json', lambda: fetch_json(MONSTERS))
	requirements = cached(cache_dir, 'requirements.json', lambda: fetch_gzipped_json(REQUIREMENTS))

	# An item name can belong to many ids (noted, placeholder, cosmetic variants). The lowest
	# equipable one is the plain item, and Bank Tags matches the variants to it.
	by_name = {}
	for item_id, name in sorted(names.items(), key=lambda kv: int(kv[0])):
		equipable = stats.get(item_id, {}).get('equipable', False)
		key = name.lower()
		if key not in by_name or (equipable and not by_name[key][1]):
			by_name[key] = (int(item_id), equipable)

	any_by_name = {}
	for item_id, name in sorted(names.items(), key=lambda kv: int(kv[0])):
		any_by_name.setdefault(name.lower(), int(item_id))

	def resolve(name):
		key = name.lower().replace('_', ' ')
		if key in ALIASES:
			return [by_name[n.lower()][0] for n in ALIASES[key] if n.lower() in by_name]
		if key not in by_name:
			key = key.split('#')[0].strip()
		return [by_name[key][0]] if key in by_name and by_name[key][1] else []

	unmapped = collections.Counter()
	unmapped_supplies = collections.Counter()
	activities = []
	for title in sorted(pages):
		text = pages[title]
		loadouts = []
		found = list(tables(text))
		inventories = [(at, inventory_names(t)) for at, t in tables(text, 'inventory')]
		paired = paired_inventories([at for at, _ in found], inventories) if found else []
		for (start, table), inventory in zip(found, paired):
			f = fields(table)
			slots = {}
			for slot in SLOTS:
				tiers, seen = [], set()
				for n in range(1, 10):
					ids = []
					for name in item_names(f.get('%s%d' % (slot, n), '')):
						found = [i for i in resolve(name) if i not in seen]
						if not found and not resolve(name):
							unmapped[name] += 1
						ids += found
						seen.update(found)
					if ids:
						tiers.append(ids)
				if tiers:
					slots[slot] = tiers
			if slots:
				label = tab_name(text, start) or f.get('style') or ''
				loadout = {'name': re.sub(r"'{2,}|\[\[|\]\]", '', label).strip(), 'slots': slots}
				supplies = []
				for name in inventory or []:
					# the wiki writes a stack as name, backslash, amount, and leaves the dose or charge off some names
					name = re.sub(r'\\\d+$', '', name).strip()
					plain = name.lower().replace(' (tablet)', '')
					item_id = next((any_by_name[n] for n in (name.lower(), plain, plain + '(4)', plain + '(8)') if n in any_by_name), None)
					if item_id is None:
						unmapped_supplies[name] += 1
					elif item_id not in supplies:
						supplies.append(item_id)
				if supplies:
					loadout['inventory'] = supplies
				loadouts.append(loadout)
		for n, loadout in enumerate(loadouts):
			if not loadout['name'] or [l['name'] for l in loadouts].count(loadout['name']) > 1:
				loadout['name'] = ('%s %d' % (loadout['name'] or 'Setup', n + 1)).strip()
		if loadouts:
			name = re.sub(r'^Slayer task/|/Strategies$', '', title)
			activities.append({'name': name, 'loadouts': loadouts})

	known = {a['name']: a for a in activities}
	general = known[SLAYER_FALLBACK]['loadouts']
	for task in cached(cache_dir, 'slayer_tasks.json', fetch_slayer_tasks):
		if task in known:
			continue
		activity = {'name': task, 'fallback': SLAYER_FALLBACK}
		types, element = monster_kinds(task, monsters)
		rules = [TYPE_RULES[t] for t in types if t in TYPE_RULES]
		notes = [rule['note'] for rule in rules] + (['Weak to %s spells.' % element] if element else [])
		if rules:
			activity['loadouts'] = adjusted(general, rules, resolve)
		if notes:
			activity['note'] = ' '.join(notes)
		activities.append(activity)

	OUTPUT = os.path.join(RESOURCES, 'activities.json')

	# What changed at the top of a ladder since the data that is about to be replaced
	def tops(data):
		return {(a['name'], l['name'], slot): tiers[0] for a in data for l in a.get('loadouts', []) for slot, tiers in l['slots'].items()}
	changes = []
	if os.path.exists(OUTPUT):
		with open(OUTPUT) as f:
			before = tops(json.load(f))
		for key, top in tops(activities).items():
			if key in before and top[0] not in before[key]:
				changes.append({'activity': key[0], 'loadout': key[1], 'slot': key[2], 'item': top[0]})
	with open(os.path.join(RESOURCES, 'changes.json'), 'w') as f:
		json.dump({'version': datetime.date.today().isoformat(), 'changes': changes}, f, separators=(',', ':'))

	with open(OUTPUT, 'w') as f:
		json.dump(activities, f, separators=(',', ':'))
	print('%d setups have an inventory; %d top picks changed' % (
		sum('inventory' in l for a in activities for l in a.get('loadouts', [])), len(changes)))
	print('unmapped supplies: %s' % unmapped_supplies.most_common(25))

	# Requirements are looked up by id, then by name for the variants the source lists under another id
	by_id = {r['id']: r['skills'] for r in requirements if r.get('skills')}
	by_item_name = {}
	for item_id, skills in by_id.items():
		by_item_name.setdefault(names.get(str(item_id), '').lower(), skills)
	ladder_items = {i for a in activities for l in a.get('loadouts', []) for tiers in l['slots'].values() for tier in tiers for i in tier}
	needed = {}
	for item_id in sorted(ladder_items):
		skills = by_id.get(item_id) or by_item_name.get(names[str(item_id)].lower())
		if skills:
			needed[item_id] = skills
	with open(os.path.join(RESOURCES, 'requirements.json'), 'w') as f:
		json.dump(needed, f, separators=(',', ':'))
	print('%d of %d ladder items have skill requirements' % (len(needed), len(ladder_items)))
	print('%d activities (%d using the slayer fallback), %d loadouts, %d bytes' % (len(activities),
		sum('fallback' in a for a in activities), sum(len(a.get('loadouts', [])) for a in activities), os.path.getsize(OUTPUT)))
	print('unmapped names: %d distinct, most common: %s' % (len(unmapped), unmapped.most_common(25)))


if __name__ == '__main__':
	main()
