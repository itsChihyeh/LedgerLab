#!/usr/bin/env python3
"""Prints the public on-demand prices that docs/deploy/aws-plan.md needs, from AWS's public Price List files.

Read-only HTTPS GETs against pricing.us-east-1.amazonaws.com; no AWS credentials, no account, no resources.
The files are large (the EC2 one for a region is hundreds of MB), so each is parsed as a stream: only the
products that match a query and their on-demand terms are ever held in memory. Items the files do not contain
print NOT FOUND; nothing is guessed.

Usage:
  python3 deploy/price-check.py [region]                (default ca-central-1)
  python3 deploy/price-check.py --list OFFER [region]   list every product of one offer (for example
                                                        AmazonCloudWatch, AmazonSES) with its on-demand prices
"""
import codecs
import datetime
import json
import sys
import urllib.request

BASE = 'https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/%s/current/%s/index.json'
CHUNK = 1 << 20
DECODER = json.JSONDecoder()
INSTANCE_TYPES = {'t4g.small', 't4g.medium', 't3.small'}


class JsonStream:
    """Incremental reader over a JSON document that arrives as text chunks."""

    def __init__(self, read_chunk):
        self.read_chunk = read_chunk
        self.buf = ''
        self.pos = 0
        self.eof = False

    def fill(self):
        if self.eof:
            return False
        data = self.read_chunk()
        if not data:
            self.eof = True
            return False
        self.buf = self.buf[self.pos:] + data
        self.pos = 0
        return True

    def peek(self):
        while True:
            while self.pos < len(self.buf) and self.buf[self.pos] in ' \t\r\n':
                self.pos += 1
            if self.pos < len(self.buf):
                return self.buf[self.pos]
            if not self.fill():
                return ''

    def expect(self, char):
        if self.peek() != char:
            raise ValueError('expected %r in price file' % char)
        self.pos += 1

    def value(self):
        """Decodes one complete value, reading more input while it is cut off by the chunk boundary."""
        self.peek()
        while True:
            try:
                result, end = DECODER.raw_decode(self.buf, self.pos)
                # A bare number that ends exactly at the chunk end may be cut short; containers and strings cannot.
                if end < len(self.buf) or self.eof or self.buf[self.pos] in '{["':
                    self.pos = end
                    return result
            except json.JSONDecodeError:
                if self.eof:
                    raise
            if not self.fill():
                if self.pos >= len(self.buf):
                    raise ValueError('price file ended inside a value')

    def members(self):
        """Yields the keys of the next object; the caller must consume each key's value before the next step."""
        self.expect('{')
        while True:
            char = self.peek()
            if char == '}':
                self.pos += 1
                return
            if char == ',':
                self.pos += 1
                continue
            key = self.value()
            self.expect(':')
            yield key


def open_stream(offer, region):
    response = urllib.request.urlopen(BASE % (offer, region), timeout=60)
    decoder = codecs.getincrementaldecoder('utf-8')()

    def read_chunk():
        data = response.read(CHUNK)
        return decoder.decode(data, final=not data)

    return JsonStream(read_chunk)


def scan(offer, region, wanted):
    """Returns (publication date, [(attributes, [price dimension, ...]), ...]) for products where wanted(attrs)."""
    stream = open_stream(offer, region)
    products = {}
    published = None
    prices = {}
    for key in stream.members():
        if key == 'publicationDate':
            published = stream.value()
        elif key == 'products':
            for sku in stream.members():
                product = stream.value()
                attrs = dict(product.get('attributes', {}), productFamily=product.get('productFamily'))
                if wanted(attrs):
                    products[sku] = attrs
        elif key == 'terms':
            for kind in stream.members():
                if kind != 'OnDemand':
                    stream.value()
                    continue
                for sku in stream.members():
                    offers = stream.value()
                    if sku in products:
                        prices[sku] = [dim for offer_term in offers.values()
                                       for dim in offer_term['priceDimensions'].values()]
                break
            break  # OnDemand is the last thing we need; the large Reserved section is never read.
        else:
            stream.value()
    return published, [(products[sku], prices.get(sku, [])) for sku in products]


def show(label, found):
    if not found:
        print('%-34s NOT FOUND' % label)
        return
    for attrs, dims in sorted(found, key=lambda item: (item[0].get('usagetype', ''), item[0].get('instanceType', ''))):
        name = attrs.get('instanceType') or attrs.get('usagetype', '?')
        if not dims:
            print('%-34s %s: no on-demand price' % (label, name))
        for dim in dims:
            usd = dim['pricePerUnit'].get('USD')
            tier = '' if dim.get('beginRange') in (None, '0') and dim.get('endRange') in (None, 'Inf') \
                else ' [tier %s..%s]' % (dim.get('beginRange'), dim.get('endRange'))
            print('%-34s %s: %s USD per %s%s' % (label, name, usd, dim['unit'], tier))
            print('%-34s   (%s)' % ('', dim.get('description', '')))


def ends(attrs, *suffixes):
    return attrs.get('usagetype', '').endswith(suffixes)


def list_offer(offer, region):
    published, found = scan(offer, region, lambda attrs: True)
    print('%s %s published %s' % (offer, region, published))
    show(offer, found)


def main():
    args = sys.argv[1:]
    list_offer_code = None
    if args[:1] == ['--list'] and len(args) >= 2:
        list_offer_code, args = args[1], args[2:]
    region = args[0] if args else 'ca-central-1'
    print('Queried %s UTC, region %s' % (datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d'), region))
    if list_offer_code:
        list_offer(list_offer_code, region)
        return

    published, found = scan('AmazonEC2', region, lambda a: (
        a.get('instanceType') in INSTANCE_TYPES and a.get('operatingSystem') == 'Linux'
        and a.get('tenancy') == 'Shared' and a.get('preInstalledSw') == 'NA' and a.get('capacitystatus') == 'Used'))
    print('-- AmazonEC2 (price list published %s)' % published)
    show('EC2 Linux on-demand', found)
    published, found = scan('AmazonEC2', region, lambda a: (
        a.get('productFamily') == 'Storage' and a.get('volumeApiName') == 'gp3'))
    show('EBS gp3 storage', found)

    published, found = scan('AmazonVPC', region, lambda a: ends(a, 'PublicIPv4:InUseAddress', 'PublicIPv4:IdleAddress'))
    print('-- AmazonVPC (price list published %s)' % published)
    show('Public IPv4 address', found)

    published, found = scan('AmazonCloudWatch', region, lambda a: (
        a.get('usagetype', '').split('-', 1)[-1] in ('DataProcessing-Bytes', 'TimedStorage-ByteHrs', 'CW:MetricMonitorUsage')))
    print('-- AmazonCloudWatch (price list published %s)' % published)
    show('CloudWatch', found)

    published, found = scan('AmazonSES', region, lambda a: a.get('usagetype', '').split('-', 1)[-1] in (
        'Message', 'Essentials-Outbound-Email', 'AttachmentsSize-Bytes'))
    print('-- AmazonSES (price list published %s)' % published)
    show('SES', found)
    print('Lightsail, Fargate, RDS and ALB: read them from the pages listed in aws-plan.md.')


if __name__ == '__main__':
    main()
