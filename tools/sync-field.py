from pathlib import Path
import sys

assets = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/cloudscape_volumetric'
field = (assets / 'field-shapes.glsl').read_text(encoding='utf-8')
for name in ['density.glsl', 'density-atlas.glsl']:
    path = assets / name
    source = path.read_text(encoding='utf-8')
    marker = '// BEGIN GENERATED SHAPES: tools/sync-field.py\n'
    start = source.index(marker) + len(marker)
    end = source.index('// END GENERATED SHAPES', start)
    expected = source[:start] + field + source[end:]
    if '--check' in sys.argv:
        if source != expected: raise SystemExit(name + ' differs from the shared field')
    else: path.write_text(expected, encoding='utf-8', newline='\n')
print('Shared density field matches procedural and atlas paths.')
