"""Rebuild transaction SVGs: python3 docs/diagrams/render_transactions.py.

Only Python's standard library is required. Event order expresses protocol
causality, not cycle timing. Ring paths follow ChiSystem connection order.
"""
from dataclasses import dataclass
from html import escape
from pathlib import Path


NODES = ('RN0', 'HN', 'RN1', 'SN')
COLORS = {'REQ': '#2563eb', 'RSP': '#15803d', 'SNP': '#c45c06', 'DAT': '#dc2626'}
CHANNELS = {'REQ': 0, 'RSP': 1, 'SNP': 2, 'DAT': 3}
POSITIONS = ((129, 149), (341, 149), (341, 311), (129, 311))
# Clockwise edges: 0 -> 1 -> 2 -> 3 -> 0. Endpoints leave room for node boxes.
EDGES = (
    'M 179 126 C 215 102 255 102 291 126',
    'M 369 173 C 404 204 404 256 369 287',
    'M 291 334 C 255 358 215 358 179 334',
    'M 101 287 C 66 256 66 204 101 173',
)
BADGES = ((235, 111), (395, 230), (235, 352), (75, 230))


@dataclass(frozen=True)
class Flit:
    channel: str
    src: int
    dst: int
    opcode: str
    fields: str

    def route(self):
        # A self-directed local injection must also traverse the entire ring.
        path = [self.src]
        while len(path) == 1 or path[-1] != self.dst:
            path.append((path[-1] + 1) % 4)
        return path


def f(channel, src, dst, opcode, fields):
    return Flit(channel, src, dst, opcode, fields)


def memory_read():
    return [f('REQ', 1, 3, 'ReadNoSnp', 'txnID=h=0; addr=A'),
            f('DAT', 3, 1, 'CompData', 'txnID=h=0; resp=I; data=M[A]')]


def memory_write():
    return [f('REQ', 1, 3, 'WriteNoSnpFull', 'txnID=h=0; addr=A'),
            f('RSP', 3, 1, 'DBIDResp', 'txnID=h=0; dbID=dS=0'),
            f('DAT', 1, 3, 'NonCopyBackWrData', 'txnID=dS=0; resp=I; data=D'),
            f('RSP', 3, 1, 'Comp', 'txnID=h=0; dbID=dS=0')]


def ack():
    return f('RSP', 0, 1, 'CompAck', 'txnID=dH=0; dbID=0')


def read_request(opcode='ReadShared'):
    return f('REQ', 0, 1, opcode, 'txnID=t; addr=A; allowRetry=1')


def completion(resp, data='D'):
    return f('DAT', 1, 0, 'CompData', f'txnID=t; dbID=dH=0; resp={resp}; data={data}')


def retry(src, dst, txn):
    return [f('REQ', src, dst, 'ReadNoSnp', f'txnID={txn}; addr=A; allowRetry=1'),
            f('RSP', dst, src, 'RetryAck', f'txnID={txn}; pCrdType=0'),
            f('RSP', dst, src, 'PCrdGrant', 'txnID=0; pCrdType=0'),
            f('REQ', src, dst, 'ReadNoSnp', f'txnID={txn}; addr=A; allowRetry=0')]


SCENARIOS = {
    'read-nosnp': [read_request('ReadNoSnp'), *memory_read(), completion('I', 'M[A]')],
    'write-nosnp': [f('REQ', 0, 1, 'WriteNoSnpFull', 'txnID=t; addr=A; allowRetry=1'),
                    f('RSP', 1, 0, 'CompDBIDResp', 'txnID=t; dbID=dH=0'),
                    f('DAT', 0, 1, 'NonCopyBackWrData', 'txnID=dH=0; resp=I; data=D'),
                    *memory_write()],
    'read-shared': [read_request(), *memory_read(), completion('UC', 'M[A]'), ack()],
    'snoop-clean': [read_request(),
                    f('SNP', 1, 2, 'SnpShared', 'txnID=s=0; addr=A'),
                    f('RSP', 2, 1, 'SnpResp', 'txnID=s=0; resp=SC'),
                    *memory_read(), completion('SC', 'M[A]'), ack()],
    'snoop-dirty-shared': [read_request(),
                           f('SNP', 1, 2, 'SnpShared', 'txnID=s=0; addr=A'),
                           f('DAT', 2, 1, 'SnpRespData', 'txnID=s=0; resp=SC_PD; data=D'),
                           *memory_write(), completion('SC'), ack()],
    'snoop-dirty-unique': [read_request('ReadUnique'),
                           f('SNP', 1, 2, 'SnpUnique', 'txnID=s=0; addr=A'),
                           f('DAT', 2, 1, 'SnpRespData', 'txnID=s=0; resp=I_PD; data=D'),
                           completion('UD_PD'), ack()],
    'evict': [f('REQ', 0, 1, 'Evict', 'txnID=t; addr=A; allowRetry=1'),
              f('RSP', 1, 0, 'Comp', 'txnID=t; resp=I')],
    'writeback': [f('REQ', 0, 1, 'WriteBackFull', 'txnID=t; addr=A; allowRetry=1'),
                  f('RSP', 1, 0, 'CompDBIDResp', 'txnID=t; dbID=dH=0'),
                  f('DAT', 0, 1, 'CopyBackWrData', 'txnID=dH=0; resp=UD_PD; data=D'),
                  *memory_write()],
    'retry-hn': retry(0, 1, 't'),
    'retry-sn': retry(1, 3, 'h=0'),
}


def label(x, y, value, size=14, color='#334155', weight='normal'):
    return (f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" '
            f'font-weight="{weight}" text-anchor="middle">{escape(value)}</text>')


def panel(index, event):
    color = COLORS[event.channel]
    path = event.route()
    traversed = path[:-1]
    out = ['<rect x="8" y="5" width="464" height="436" rx="12" '
           'fill="#fff" stroke="#cbd5e1"/>']
    out.append(label(240, 32, f'{index:02}  {event.channel} / channel{CHANNELS[event.channel]}',
                     16, color, 'bold'))
    out.append(label(240, 57, event.opcode, 20, '#0f172a', 'bold'))
    out.append(label(240, 79, event.fields, 12))
    for edge, shape in enumerate(EDGES):
        active = edge in traversed
        stroke = color if active else '#cbd5e1'
        marker = event.channel if active else 'idle'
        out.append(f'<path d="{shape}" fill="none" stroke="{stroke}" '
                   f'stroke-width="{5 if active else 2}" marker-end="url(#{marker})"/>')
        if active:
            x, y = BADGES[edge]
            hop = traversed.index(edge) + 1
            out.append(f'<circle cx="{x}" cy="{y}" r="11" fill="{color}"/>')
            out.append(label(x, y + 4, str(hop), 12, '#fff', 'bold'))
    for node, (x, y) in enumerate(POSITIONS):
        status = ('inject' if node == event.src else
                  'eject' if node == event.dst else
                  'forward' if node in path else 'idle')
        active = status != 'idle'
        out.append(f'<rect x="{x - 52}" y="{y - 24}" width="104" height="48" '
                   f'rx="16" fill="{color if active else "#f1f5f9"}" '
                   f'stroke="{color if active else "#cbd5e1"}"/>')
        out.append(label(x, y - 3, NODES[node], 17, '#fff' if active else '#475569', 'bold'))
        out.append(label(x, y + 15, f'Stop{node}', 12, '#fff' if active else '#64748b'))
        status_y = y + 43 if node < 2 else y - 36
        out.append(label(x, status_y, status, 12, color if active else '#94a3b8'))
    out.append(label(235, 224, event.channel + ' ring', 17, color, 'bold'))
    out.append(label(235, 248, f'RingFlit.dst = {event.dst}', 13))
    out.append(label(240, 399, f'srcID={event.src}   tgtID={event.dst}' if event.channel != 'SNP'
                     else f'srcID={event.src}   RoutedSnp.dst={event.dst}', 13))
    out.append(label(240, 423, ' → '.join(f'Stop{node}' for node in path), 14))
    return '\n'.join(out)


def render(name, events):
    rows = (len(events) + 1) // 2
    title = f'Physical ring: {name}'
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="960" height="{rows * 450 + 44}" '
           f'viewBox="0 0 960 {rows * 450 + 44}" role="img" aria-labelledby="title desc">',
           f'<title id="title">{escape(title)}</title>',
           '<desc id="desc">Read panels left to right, top to bottom. '
           'Each panel shows one flit on a clockwise four-stop ring. '
           'Colored edges and numbered hops show its full route; gray edges are unused. '
           'Panels express protocol dependencies, not cycles.</desc>',
           '<defs>']
    for key, color in {**COLORS, 'idle': '#cbd5e1'}.items():
        out.append(f'<marker id="{key}" viewBox="0 0 10 10" refX="9" refY="5" '
                   'markerWidth="7" markerHeight="7" orient="auto" markerUnits="userSpaceOnUse">'
                   f'<path d="M0 0 L10 5 L0 10 Z" fill="{color}"/></marker>')
    out.extend(['</defs>', '<rect width="100%" height="100%" fill="#f8fafc"/>',
                '<g font-family="DejaVu Sans,Arial,sans-serif">',
                label(480, 25, 'REQ: blue   RSP: green   SNP: orange   DAT: red  |  1,2,3 = hops', 14)])
    for i, event in enumerate(events):
        out.append(f'<g transform="translate({(i % 2) * 480},{36 + (i // 2) * 450})">')
        out.append(panel(i + 1, event))
        out.append('</g>')
    out.extend(['</g>', '</svg>'])
    Path(__file__).with_name(name + '.svg').write_text('\n'.join(out) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name, events in SCENARIOS.items():
        render(name, events)
    print(f'Rendered {len(SCENARIOS)} SVGs, {sum(map(len, SCENARIOS.values()))} flit panels.')
