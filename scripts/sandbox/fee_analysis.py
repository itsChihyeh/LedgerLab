"""FEE_MISMATCH explained payment by payment (AC-8.9 evidence). Read-only: app API plus Stripe GETs via the relay."""
import collections
import json
import sys

from payout_refund_recon import stripe_get
from runtime import APP, OUT, Failure, emit, get_json, instant, require, write_json


def fetch_rows():
    issues = get_json(APP, '/api/recon/issues?status=OPEN&limit=100')['issues']
    rows = []
    for issue in issues:
        if issue['category'] != 'FEE_MISMATCH':
            continue
        ref = next(e['processor_ref'] for e in issue['evidenceRefs'] if e.get('processor_ref'))
        charge = stripe_get('/v1/charges/' + ref, [('expand[]', 'balance_transaction')])
        card = (charge.get('payment_method_details') or {}).get('card') or {}
        txn = charge['balance_transaction']
        fees = issue['actual']['fees']
        rows.append({'charge': ref, 'amount': charge['amount'], 'currency': charge['currency'],
                     'country': card.get('country'), 'brand': card.get('brand'),
                     'stripe_fee': txn['fee'], 'issue_actual_fee': sum(f['fee'] for f in fees),
                     'local_fee': issue['expected']['fee'],
                     'fee_details': [{'type': d['type'], 'amount': d['amount'], 'description': d.get('description')}
                                     for d in txn.get('fee_details', [])]})
    return sorted(rows, key=lambda r: (r['country'] or '', r['amount'], r['charge']))


def percent(amount, bps):
    """Round-half-up basis-point share, integer arithmetic only."""
    return (amount * bps + 5000) // 10000


def derive(rows):
    """Per country: how much of the surplus over the local fee is a flat amount, how much is proportional.

    Returns one entry per country. `rule` is an exact fit only when one (fixed, bps) pair reproduces the
    Stripe fee of every row in the group; rows that fit nothing are listed under `unexplained`.
    """
    groups = collections.defaultdict(list)
    for row in rows:
        row['difference'] = row['stripe_fee'] - row['local_fee']
        groups[row['country'] or 'UNKNOWN'].append(row)
    result = []
    for country, members in sorted(groups.items()):
        fit = None
        for fixed in range(0, 101):
            for bps in range(0, 801):
                if all(r['stripe_fee'] == fixed + percent(r['amount'], bps) for r in members):
                    fit = {'fixed': fixed, 'bps': bps}
                    break
            if fit:
                break
        diffs = sorted({r['difference'] for r in members})
        surplus = None
        if len(members) > 1:
            # difference = a + b*amount; solve exactly from the extreme rows, then verify on all
            lo, hi = min(members, key=lambda r: r['amount']), max(members, key=lambda r: r['amount'])
            if lo['amount'] != hi['amount']:
                bps = round((hi['difference'] - lo['difference']) * 10000 / (hi['amount'] - lo['amount']))
                fixed = lo['difference'] - percent(lo['amount'], bps)
                if all(r['difference'] == fixed + percent(r['amount'], bps) for r in members):
                    surplus = {'fixed': fixed, 'bps': bps}
        result.append({'country': country, 'count': len(members), 'brands': sorted({r['brand'] or '?' for r in members}),
                       'amount_range': [min(r['amount'] for r in members), max(r['amount'] for r in members)],
                       'differences': diffs, 'stripe_fee_rule': fit, 'surplus_over_local_rule': surplus,
                       'unexplained': [r['charge'] for r in members if fit is None]})
    return result


def analyze():
    rows = fetch_rows()
    require(rows, 'NO_FEE_MISMATCH_OPEN')
    require(all(r['stripe_fee'] == r['issue_actual_fee'] for r in rows), 'STRIPE_FEE_DISAGREES_WITH_FINDING')
    return {'generated_at': instant(), 'count': len(rows), 'rows': rows, 'by_country': derive(rows)}


def main():
    try:
        value = analyze()
        write_json(OUT / 'fee-analysis.json', value)
        emit({'result': 'PASS', 'count': value['count'], 'countries': value['by_country']})
        return 0
    except Failure as failure:
        emit({'result': 'FAIL', 'reason': str(failure)})
        return failure.exit_code


if __name__ == '__main__':
    sys.exit(main())
