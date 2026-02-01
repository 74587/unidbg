#!/usr/bin/env python3
"""
工具：使用本项目提供的 HTTP 接口批量拉取章节并合并成 TXT。

仅需提供书籍 ID 与书名即可下载：
    python3 tools/download_book.py 1630621903720459 "书名"
"""

import argparse
import html
import json
import os
import re
import sys
import urllib.error
import urllib.request
from typing import Dict, Iterable, List, Optional, Tuple


DEFAULT_BASE = 'http://127.0.0.1:9999'


def http_json(method: str, url: str, body: Optional[dict], timeout: int) -> dict:
    data = None
    headers = {
        'Accept': 'application/json',
        'Content-Type': 'application/json; charset=utf-8',
        'User-Agent': 'fqnovel-tools/1.0',
    }
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(url, data=data, method=method.upper(), headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode('utf-8', errors='replace')
        try:
            return json.loads(raw)
        except Exception:
            raise RuntimeError(f'Invalid JSON from {url}: {raw[:200]}')


def html_to_text(raw: str) -> str:
    if not raw:
        return ''
    raw = raw.replace('<br>', '\n').replace('<br/>', '\n').replace('<br />', '\n')
    raw = re.sub(r'</p\\s*>', '\n', raw, flags=re.I)
    text = re.sub(r'<[^>]+>', '', raw)
    text = html.unescape(text)
    text = re.sub(r'\n{3,}', '\n\n', text).strip()
    return text


def sanitize_filename(name: str) -> str:
    name = name.strip()
    if not name:
        return 'book'
    return re.sub(r'[\\\\/\\n\\r\\t:*?"<>|]', '_', name)


def iter_chunks(items: List[str], size: int) -> Iterable[List[str]]:
    if size <= 0:
        size = 1
    for i in range(0, len(items), size):
        yield items[i : i + size]


def fetch_directory_items(base: str, book_id: str, timeout: int) -> List[Tuple[str, str]]:
    url = f'{base}/api/fqsearch/directory/{book_id}'
    root = http_json('GET', url, None, timeout=timeout)
    if root.get('code') not in (0, None):
        raise RuntimeError(f"directory failed: code={root.get('code')} msg={root.get('msg')}")

    data = root.get('data') or {}
    items = data.get('item_data_list') or data.get('itemDataList') or []
    results: List[Tuple[str, str]] = []
    for it in items:
        if not isinstance(it, dict):
            continue
        cid = str(it.get('item_id') or it.get('itemId') or '').strip()
        title = str(it.get('title') or '').strip()
        if cid:
            results.append((cid, title))
    return results


def fetch_batch_chapters(
    base: str,
    book_id: str,
    chapter_ids: List[str],
    token: Optional[str],
    device_id: Optional[str],
    iid: Optional[str],
    timeout: int,
) -> Dict[str, dict]:
    url = f'{base}/api/fqnovel/chapters/batch'
    payload = {
        'bookId': book_id,
        'chapterIds': chapter_ids,
    }
    if token:
        payload['token'] = token
    if device_id:
        payload['deviceId'] = device_id
    if iid:
        payload['iid'] = iid

    root = http_json('POST', url, payload, timeout=timeout)
    if root.get('code') not in (0, None):
        raise RuntimeError(f"batch failed: code={root.get('code')} msg={root.get('msg')}")
    data = root.get('data') or {}
    chapters = data.get('chapters') or {}
    if not isinstance(chapters, dict):
        return {}
    return chapters


def main() -> None:
    parser = argparse.ArgumentParser(description='Download a book via batch chapter API and merge into TXT')
    parser.add_argument('book_id', help='bookId')
    parser.add_argument('book_name', help='bookName (for output filename)')
    parser.add_argument('--base', default=DEFAULT_BASE, help=f'Server base URL (default: {DEFAULT_BASE})')
    parser.add_argument('--batch', dest='batch_size', type=int, default=30, help='Batch size per request (default: 30)')
    parser.add_argument('--max', dest='max_chapters', type=int, default=None, help='Limit number of chapters to download')
    parser.add_argument('--timeout', type=int, default=60, help='HTTP timeout seconds per request (default: 60)')
    parser.add_argument('--token', default=None, help='User token for paid chapters (optional)')
    parser.add_argument('--device-id', default=None, help='Override deviceId (optional)')
    parser.add_argument('--iid', default=None, help='Override installId (iid) (optional)')
    parser.add_argument('--output', dest='output_path', default=None, help='Explicit output TXT path (optional)')
    parser.add_argument('--no-titles', action='store_true', help='Do not write chapter titles, only content')

    args = parser.parse_args()

    book_id = args.book_id.strip()
    book_name = args.book_name.strip()
    if not book_id:
        raise SystemExit('book_id is empty')
    if not book_name:
        raise SystemExit('book_name is empty')

    base = args.base.rstrip('/')
    batch_size = max(1, min(50, int(args.batch_size)))
    timeout = max(1, int(args.timeout))

    safe_name = sanitize_filename(book_name)
    output_path = args.output_path
    if not output_path:
        output_dir = 'results/novels'
        output_path = os.path.join(output_dir, f'{safe_name}.txt')

    print(f'==> Fetching directory: bookId={book_id}')
    directory_items = fetch_directory_items(base, book_id, timeout=timeout)
    if not directory_items:
        raise SystemExit('No chapters found in directory response')

    if args.max_chapters is not None:
        directory_items = directory_items[: int(args.max_chapters)]

    os.makedirs(os.path.dirname(output_path) or '.', exist_ok=True)

    total = len(directory_items)
    done = 0
    missing = 0

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(f'小说名：{book_name}\n')
        f.write(f'书籍ID：{book_id}\n\n')

        # 按目录顺序批量拉取，再顺序写入
        ids_only = [cid for (cid, _) in directory_items]
        title_map = {cid: title for (cid, title) in directory_items}

        for chunk in iter_chunks(ids_only, batch_size):
            try:
                chapters = fetch_batch_chapters(
                    base=base,
                    book_id=book_id,
                    chapter_ids=chunk,
                    token=args.token,
                    device_id=args.device_id,
                    iid=args.iid,
                    timeout=timeout,
                )
            except urllib.error.HTTPError as e:
                raise RuntimeError(f'HTTPError {e.code} for batch request: {e.read().decode("utf-8", errors="replace")}')

            for cid in chunk:
                info = chapters.get(cid) if isinstance(chapters, dict) else None
                if not isinstance(info, dict):
                    missing += 1
                    continue

                chapter_name = (info.get('chapterName') or title_map.get(cid) or '').strip()
                txt = (info.get('txtContent') or '').strip()
                if not txt:
                    txt = html_to_text((info.get('rawContent') or '').strip())

                if not args.no_titles:
                    # 统一空行分隔，避免内容粘连
                    if chapter_name:
                        f.write(chapter_name + '\n\n')
                    else:
                        f.write('\n')
                if txt:
                    f.write(txt)
                    if not txt.endswith('\n'):
                        f.write('\n')
                f.write('\n')
                done += 1

            print(f'==> Progress: {done}/{total} (missing={missing})', file=sys.stderr)

    print(f'OK: merged {done}/{total} chapters -> {output_path}')


if __name__ == '__main__':
    main()
