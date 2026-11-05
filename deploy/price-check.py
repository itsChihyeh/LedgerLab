#!/usr/bin/env python3
"""Prints the public on-demand prices that docs/deploy/aws-plan.md needs, from AWS's public Price List files.

Read-only HTTPS GETs against pricing.us-east-1.amazonaws.com; no AWS credentials, no account, no resources.
The EC2 file for one region is large (hundreds of MB), so it is streamed to a temporary file and parsed once.
Usage: python3 deploy/price-check.py [region]   (default ca-central-1)
"""
import datetime
import json
import sys
import tempfile
import urllib.request

REGION = sys.argv[1] if len(sys.argv) > 1 else 'ca-central-1'
URL = 'https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/%s/index.json' % REGION
WANTED_TYPES = {'t4g.small', 't4g.medium'}


def on_demand_usd(terms, sku):
    for offer in terms.get('OnDemand', {}).get(sku, {}).values():
        for dimension in offer['priceDimensions'].values():
            return dimension['pricePerUnit'].get('USD'), dimension['unit']
    return None, None


def main():
    print('Queried %s UTC, region %s' % (datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d'), REGION))
    with tempfile.TemporaryFile() as tmp:
        with urllib.request.urlopen(URL, timeout=60) as response:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                tmp.write(chunk)
        tmp.seek(0)
        offer = json.load(tmp)
    for sku, product in offer['products'].items():
        attrs = product.get('attributes', {})
        if attrs.get('instanceType') in WANTED_TYPES and attrs.get('operatingSystem') == 'Linux' \
                and attrs.get('tenancy') == 'Shared' and attrs.get('preInstalledSw') == 'NA' \
                and attrs.get('capacitystatus') == 'Used':
            price, unit = on_demand_usd(offer['terms'], sku)
            print('EC2 %-11s Linux on-demand: %s USD per %s' % (attrs['instanceType'], price, unit))
    print('EBS gp3, public IPv4, Lightsail, Fargate, RDS and ALB: read them from the pages listed in aws-plan.md.')


if __name__ == '__main__':
    main()
