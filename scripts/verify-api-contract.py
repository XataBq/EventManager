"""Offline structure/example checks for the proposed API; no backend or Android tests."""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--validator-path', type=Path, help='Optional isolated openapi-spec-validator install')
args = parser.parse_args()
if args.validator_path:
    sys.path.insert(0, str(args.validator_path.resolve()))

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

spec = json.loads((ROOT / 'docs/api/openapi.json').read_text(encoding='utf-8'))
examples = json.loads((ROOT / 'docs/api/examples.json').read_text(encoding='utf-8'))
coverage = (ROOT / 'docs/api/coverage.md').read_text(encoding='utf-8')
catalog = (ROOT / 'docs/api/endpoints.md').read_text(encoding='utf-8')
schemas = spec['components']['schemas']
errors = []

def check(ok, message):
    if not ok:
        errors.append(message)

def walk(value):
    if isinstance(value, dict):
        if '$ref' in value:
            target = value['$ref']
            check(target.startswith('#/components/schemas/'), 'Unexpected external ref: ' + target)
            check(target.rsplit('/', 1)[-1] in schemas, 'Missing ref: ' + target)
        for child in value.values():
            walk(child)
    elif isinstance(value, list):
        for child in value:
            walk(child)

walk(spec)
uri = 'https://eventpass.example/schema-root'
registry = Registry().with_resource(uri, Resource.from_contents(spec, default_specification=DRAFT202012))
for name, schema in schemas.items():
    try:
        Draft202012Validator.check_schema(schema)
    except Exception as exc:
        errors.append(f'Invalid JSON schema {name}: {exc}')

operations = {}
for path, methods in spec['paths'].items():
    for method, operation in methods.items():
        oid = operation['operationId']
        check(oid not in operations, 'Duplicate operationId: ' + oid)
        operations[oid] = operation
        check(re.search(r'\b' + re.escape(oid) + r'\b', coverage), 'Missing coverage: ' + oid)
        check(f'`{method.upper()} {path}`' in catalog, 'Missing catalog entry: ' + oid)
        declared = {p['name'] for p in operation.get('parameters', []) if p['in'] == 'path' and p['required']}
        check(declared == set(re.findall(r'\{([^}]+)\}', path)), 'Path params mismatch: ' + oid)
        check(bool(operation.get('responses')), 'No responses: ' + oid)
        check('security' in operation, 'Implicit auth scope: ' + oid)
        for status, response in operation['responses'].items():
            check(status != '204' or not response.get('content'), '204 has content: ' + oid)
        for p in operation.get('parameters', []):
            if p['name'] == 'Idempotency-Key':
                check(p['required'] and p['in'] == 'header', 'Bad idempotency header: ' + oid)

positive = negative = 0
request_coverage = set()
response_coverage = set()
for case in examples['cases']:
    name = case['name']
    schema_name = case['schema']
    validator = Draft202012Validator({'$ref':uri + '#/components/schemas/' + schema_name}, registry=registry, format_checker=FormatChecker())
    found = list(validator.iter_errors(case['value']))
    if case['valid']:
        positive += 1
        check(not found, f'{name}: expected valid, got ' + '; '.join(e.message for e in found[:3]))
    else:
        negative += 1
        check(bool(found), f'{name}: invalid example unexpectedly accepted')
    if 'operationId' in case:
        oid = case['operationId']
        check(oid in operations, 'Unknown example operation: ' + oid)
        if oid not in operations:
            continue
        op = operations[oid]
        if case['direction'] == 'request':
            expected = op['requestBody']['content']['application/json']['schema']['$ref'].rsplit('/',1)[-1]
            request_coverage.add(oid)
        else:
            status = str(case['status'])
            expected = op['responses'][status]['content']['application/json']['schema']['$ref'].rsplit('/',1)[-1]
            response_coverage.add((oid, status))
        check(schema_name == expected, f'{name}: schema mismatches operation')

for oid, op in operations.items():
    if 'application/json' in op.get('requestBody',{}).get('content',{}):
        check(oid in request_coverage, 'Missing JSON request example: ' + oid)
    for status, response in op['responses'].items():
        if status.startswith('2') and 'application/json' in response.get('content',{}):
            check((oid,status) in response_coverage, 'Missing success response example: ' + oid + '/' + status)

# Local Markdown targets in the delivery bundle. Anchors and remote URLs are out of scope.
pages = [ROOT/'docs/22-backend-api-contract.md', *sorted((ROOT/'docs/api').glob('*.md'))]
for page in pages:
    content = page.read_text(encoding='utf-8')
    for target in re.findall(r'\]\(([^)]+)\)', content):
        if target.startswith(('https://','http://','#')):
            continue
        check((page.parent / target.split('#')[0]).exists(), f'Broken link {page.name}: {target}')

try:
    from openapi_spec_validator import validate
except ImportError:
    print('OpenAPI full validator: SKIPPED (not installed); JSON Schema/refs/examples still checked')
else:
    try:
        validate(spec)
        print('OpenAPI full validator: PASS')
    except Exception as exc:
        errors.append('OpenAPI validation: ' + str(exc))

if errors:
    for error in errors:
        print('FAIL: ' + error)
    raise SystemExit(1)
print(f'PASS: {len(operations)} operations, {len(schemas)} schemas; {positive} valid / {negative} rejected examples; coverage, links, refs and parameters')
