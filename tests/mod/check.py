"""check.py <repo>: mod.json, settings.json, the app manifest and the Java constants agree.

- settings.json keys == the keys the app and vesd use (ves.hmg.Prefs) and their defaults
  == the defaults Actions.load() fills in and the ones ves.core.Settings documents
- choice options in settings.json == the value lists the app cycles through (Actions)
- provides.launcher class / panel widget / star_key broadcast exist in AndroidManifest.xml
- services.jar, apps.apk, launcher icon are files the build puts in the mod
- versions agree (mod.json, manifest versionName, USB kit ZIP=)
"""
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = sys.argv[1]
A = '{http://schemas.android.com/apk/res/android}'
failed = []


def check(name, ok, why=''):
    print(('PASS ' if ok else 'FAIL ') + name + ('' if ok else ': ' + why))
    if not ok:
        failed.append(name)


def read(p):
    with open(ROOT + '/' + p, encoding='utf-8') as f:
        return f.read()


mod = json.loads(read('mod/mod.json'))
st = json.loads(read('mod/settings.json'))
man = ET.fromstring(read('app/AndroidManifest.xml'))
prefs = read('android/src/ves/hmg/Prefs.java')
actions = read('app/java/ves/app/Actions.java')

keys = [s['key'] for s in st['settings']]
pk = dict(re.findall(r'(\w+) = "(\w+)"', prefs))
check('settings keys == Prefs constants', sorted(keys) == sorted(pk.values()), '%s vs %s' % (keys, sorted(pk.values())))

defaults = {s['key']: s['default'] for s in st['settings']}
for const, key in pk.items():
    m = re.search(r'o\.put\(Prefs\.%s, ([^)]+)\)' % const, actions)
    val = m and m.group(1).strip()
    want = json.dumps(defaults.get(key))
    if isinstance(defaults.get(key), bool):
        want = 'true' if defaults[key] else 'false'
    check('default %s' % key, val == want, 'Actions puts %s, settings.json has %s' % (val, want))

# only channel 0x45 exists since 0.2.1: 0x46 kept the stock navigation's voice prompts from playing
check('no audio channel choice', 'audio_type' not in keys, 'settings.json still offers audio_type')

lists = {'profile': 'PROFILES', 'mode': 'MODES', 'idle_level': 'IDLE', 'regen_level': 'REGEN',
         'response': 'RESPONSE', 'fade_above': 'FADE',
         'behavior': 'BEHAVIORS', 'pops': 'POPS', 'sound': 'SOUNDS',
         'prompts': 'PROMPTS'}

# every key ves.core.Settings reads is a setting the owner can change (0.2.0: behavior, pops, sound)
core = read('core/src/ves/core/Settings.java')
core_keys = set(re.findall(r'get\(m, "(\w+)"', core))
core_keys = {k for k in core_keys if k == k.lower()}  # camelCase ones are aliases
check('settings.json has every ves.core.Settings key', core_keys <= set(keys), 'missing %s' % sorted(core_keys - set(keys)))
for key, arr in (('behavior', 'BEHAVIORS'), ('pops', 'POPS'), ('sound', 'SOUNDS'), ('prompts', 'PROMPTS')):
    m = re.search(r'String\[\] %s = \{([^}]*)\}' % arr, core)
    want = re.findall(r'"([^"]*)"', m.group(1)) if m else None
    have = [o['value'] for s2 in st['settings'] if s2['key'] == key for o in s2['options']]
    check('choice %s options == ves.core.Settings.%s' % (key, arr), have == want, '%s vs %s' % (have, want))
# every app choice list has a name per value (0.2.6: 8 user slots)
for arr in sorted(set(lists.values())):
    m = re.search(r'static final String\[\] %s = \{([^}]*)\}' % arr, actions)
    n = re.search(r'static final String\[\] %s_NAMES = \{([^}]*)\}' % arr, actions)
    if m and n:
        check('%s_NAMES has a name per value' % arr, len(re.findall(r'"([^"]*)"', m.group(1))) == len(re.findall(r'"([^"]*)"', n.group(1))), arr)
# the app's import dialog takes the user slots from Actions, not a list of its own
main = read('app/java/ves/app/MainActivity.java')
check('MainActivity has no slot list of its own', '"user1"' not in main and 'new String[3]' not in main, 'hardcoded user slots')
check('Actions.USER_SLOTS lists user1..user8', re.search(r'USER_SLOTS = \{\s*"user1", "user2", "user3", "user4", "user5", "user6", "user7", "user8"\s*\}', actions) is not None, 'USER_SLOTS')
for s in st['settings']:
    if s['type'] != 'choice':
        continue
    m = re.search(r'static final String\[\] %s = \{([^}]*)\}' % lists[s['key']], actions)
    app = re.findall(r'"([^"]*)"', m.group(1)) if m else None
    opts = [o['value'] for o in s['options']]
    check('choice %s options == app list' % s['key'], app == opts, '%s vs %s' % (app, opts))
    check('choice %s default is an option' % s['key'], s['default'] in opts, s['default'])

pkg = man.get('package')
acts = [a.get(A + 'name') for a in man.iter('activity')]
recv = {r.get(A + 'name'): [x.get(A + 'name') for x in r.iter('action')] for r in man.iter('receiver')}
check('apps package == manifest package', mod['apps'][0]['package'] == pkg, pkg)
for le in mod['provides']['launcher']:
    check('launcher %s activity in manifest' % le['id'], le['package'] == pkg and le['class'] in acts, le['class'])
for p in mod['provides']['panel']:
    wp, wc = p['widget'].split('/')
    check('panel widget receiver in manifest', wp == pkg and wc in recv and
          'android.appwidget.action.APPWIDGET_UPDATE' in recv[wc], p['widget'])
for sk in mod['provides']['star_key']:
    check('star_key %s broadcast received' % sk['id'], any(sk['broadcast'] in v for v in recv.values()), sk['broadcast'])
    check('star_key broadcast == Actions.ACTION_TOGGLE', '"%s"' % sk['broadcast'] in actions, sk['broadcast'])

svc = mod['services'][0]
check('vesd class exists', svc['class'] == 'ves.android.Vesd', svc['class'])
check('vesd runs as system', svc['user'] == 'system', svc['user'])
check('apk path is the build output', mod['apps'][0]['apk'] == 'app/EngineSound.apk')
check('tile icon is built', mod['provides']['launcher'][0]['icon'] == 'tile.png')

ver = mod['version']
check('manifest versionName == mod version', man.get(A + 'versionName') == ver, man.get(A + 'versionName'))
check('USB kit names the package', ('ZIP=%s-%s.zip' % (mod['id'], ver)) in read('usb/VES_MOD/run.sh'))
check('vehicle reads only', 'commands' not in mod.get('vehicle', {}))
check('usb folder VES', mod.get('usb') == ['VES'])

if failed:
    print('mod: %d check(s) failed' % len(failed))
    sys.exit(1)
print('mod: all checks passed')
