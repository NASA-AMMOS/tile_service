#!/usr/bin/env python3

import json
import sys

if len(sys.argv) != 3:
    print("USAGE: filter-metadata.py INPUT_JSON OUTPUT_JSON", file=sys.stderr)
    sys.exit(1)

input_file = sys.argv[1]
output_file = sys.argv[2]

with open(input_file, 'r') as f: data = json.load(f)

if 'tiler_version' in data: del data['tiler_version']
if 'processing_timestamp' in data: del data['processing_timestamp']

with open(output_file, 'w') as f: json.dump(data, f, sort_keys=True, indent=2)
