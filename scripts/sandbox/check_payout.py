"""Read every payout page in the current run window; 75 means still waiting."""
import os
import re
import sys
from datetime import datetime
from urllib.parse import urlencode

from probe import Stripe, ProbeError
from runtime import Failure, emit, load_run, require


def check_payout(stripe, window_start):
    start = int(datetime.fromisoformat(window_start.replace('Z', '+00:00')).timestamp())
    query = {'created[gte]': start, 'limit': 100}
    cursors, payouts = set(), []
    while True:
        page = stripe.request('/v1/payouts?' + urlencode(query))
        require(isinstance(page.get('data'), list) and type(page.get('has_more')) is bool,
                'INVALID_PAYOUT_PAGE')
        for item in page['data']:
            require(isinstance(item, dict) and item.get('livemode') is False
                    and re.fullmatch(r'po_[A-Za-z0-9]+', item.get('id', ''))
                    and type(item.get('created')) is int and item['created'] >= start
                    and type(item.get('automatic')) is bool
                    and isinstance(item.get('status'), str)
                    and isinstance(item.get('reconciliation_status'), str), 'INVALID_PAYOUT')
            payouts.append(item)
        if not page['has_more']:
            break
        require(page['data'], 'EMPTY_PAYOUT_CURSOR')
        cursor = page['data'][-1]['id']
        require(cursor not in cursors, 'REPEATED_PAYOUT_CURSOR')
        cursors.add(cursor)
        query['starting_after'] = cursor
    complete = [p['id'] for p in payouts if p['automatic'] and p['status'] == 'paid'
                and p['reconciliation_status'] == 'completed']
    if complete:
        return 0, {'result': 'READY', 'payouts': complete}
    return 75, {'result': '等待中', 'payouts': [
        {k: p[k] for k in ('id', 'automatic', 'status', 'reconciliation_status')} for p in payouts]}


def main():
    try:
        code, result = check_payout(Stripe(os.environ.get('STRIPE_API_KEY', '')), load_run()['window_start'])
        emit(result)
        return code
    except (Failure, ProbeError, ValueError, KeyError, TypeError, OSError):
        emit({'result': 'FAIL', 'reason': 'PAYOUT_QUERY_FAILED'})
        return 2


if __name__ == '__main__':
    sys.exit(main())
