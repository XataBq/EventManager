"""Offline structural checks for current architecture docs; no Android/runtime tests."""
from pathlib import Path
import re
import sys
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
PAGES = [ROOT / 'docs' / name for name in (
    '00-documentation-map.md', '02-architecture.md', '03-navigation.md',
    '05-data-contracts.md', '14-engineering-rules.md',
)] + sorted((ROOT / 'docs' / 'architecture').glob('*.md'))


def main():
    failures = []
    links = 0
    diagrams = 0
    for path in PAGES:
        if not path.is_file():
            failures.append(f'Missing page: {path.relative_to(ROOT)}')
            continue
        text = path.read_text(encoding='utf-8-sig')
        if len(re.findall(r'^```', text, re.M)) % 2:
            failures.append(f'Unpaired fence: {path.relative_to(ROOT)}')
        diagrams += len(re.findall(r'^```mermaid\s*$', text, re.M))
        # Inspect prose links only; fenced examples are not navigation targets.
        prose = re.sub(r'^```[^\n]*\n.*?^```[^\n]*$', '', text, flags=re.M | re.S)
        for target in re.findall(r'\]\(([^\n)]+)\)', prose):
            target = target.strip().strip('<>')
            if re.match(r'^[a-z][a-z0-9+.-]*:', target, re.I) or target.startswith('#'):
                continue
            target = unquote(target.split('#', 1)[0])
            links += 1
            if not (path.parent / target).resolve().exists():
                failures.append(f'Broken local link in {path.relative_to(ROOT)}: {target}')
    nav = (ROOT / 'docs/03-navigation.md').read_text(encoding='utf-8')
    codes = re.findall(r'^\| (G\d\d|P\d\d|V\d\d|O\d\d) \|', nav, re.M)
    expected = {f'{prefix}{i:02}' for prefix, count in (('G', 5), ('P', 3), ('V', 5), ('O', 5)) for i in range(1, count + 1)}
    if set(codes) != expected or len(codes) != 18:
        failures.append('The mobile frame registry must contain each of the 18 IDs exactly once')
    data = (ROOT / 'docs/05-data-contracts.md').read_text(encoding='utf-8')
    scenarios = re.findall(r'^\| (S\d\d) \|', data, re.M)
    if set(scenarios) != {f'S{i:02}' for i in range(1, 15)} or len(scenarios) != 14:
        failures.append('The scenario registry must contain S01-S14 exactly once')
    # Named Kotlin tests in the invariant table must still be findable in the tree.
    invariant = (ROOT / 'docs/architecture/invariants.md').read_text(encoding='utf-8')
    modules = ('app', 'core/domain', 'core/platform', 'data/fake')
    tests = '\n'.join(
        p.read_text(encoding='utf-8-sig')
        for module in modules
        for source_set in ('test', 'testDemo', 'testRemote')
        for p in (ROOT / module / 'src' / source_set).rglob('*.kt')
    )
    for name in re.findall(r'`([a-z][A-Za-z0-9]+)`', invariant):
        if name.startswith(('relationships', 'existingAccount', 'cancelled', 'receiptIs', 'closeStart', 'guestRegistration', 'revisions', 'dirtyEditor', 'failedWrites', 'authAndTicket', 'v2Migration', 'v1Migration', 'schemaV3')):
            if not re.search(r'fun\s+' + re.escape(name) + r'\s*\(', tests):
                failures.append(f'Named test is missing: {name}')
    if failures:
        print('\n'.join(failures), file=sys.stderr)
        return 1
    print(f'OK: {len(PAGES)} current pages, {links} local links, {diagrams} Mermaid blocks, 18 mobile IDs, S01-S14 and named test references.')
    print('Mermaid rendering and Kotlin semantic correctness are not checked by this script.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
