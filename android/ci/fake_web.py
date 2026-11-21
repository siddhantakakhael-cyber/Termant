#!/usr/bin/env python3
"""A pretend YouTube and a pretend ElevenLabs for the tests: fake_web.py PORT_FILE
Listens on a free port of this machine and writes the port to PORT_FILE."""
import io
import json
import math
import struct
import sys
import wave
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

VIDEO = 'dQw4w9WgXcQ'


def renderer(vid, title):
    return {'videoRenderer': {
        'videoId': vid, 'title': {'runs': [{'text': title}]}, 'ownerText': {'runs': [{'text': 'Office Guru'}]},
        'lengthText': {'simpleText': '4:05'}, 'viewCountText': {'simpleText': '1,234 views'},
        'publishedTimeText': {'simpleText': '2 years ago'}}}


FOUND = {'contents': {'twoColumnSearchResultsRenderer': {'primaryContents': {'sectionListRenderer': {'contents': [
    {'itemSectionRenderer': {'contents': [renderer(VIDEO, 'Make a table in Word'), {'adSlotRenderer': {}},
                                          renderer('aaaaaaaaaaa', 'Word tables & more')]}}]}}}}}
PLAYED = {
    'playabilityStatus': {'status': 'OK'},
    'videoDetails': {'videoId': VIDEO, 'title': 'Make a table in Word', 'author': 'Office Guru', 'lengthSeconds': '245',
                     'shortDescription': 'Tables, step by step.'},
    'captions': {'playerCaptionsTracklistRenderer': {'captionTracks': [
        {'baseUrl': 'https://www.youtube.com/api/timedtext?v=%s&lang=de&fmt=srv3' % VIDEO, 'languageCode': 'de'},
        {'baseUrl': 'https://www.youtube.com/api/timedtext?v=%s&lang=en&kind=asr&fmt=srv3' % VIDEO, 'languageCode': 'en', 'kind': 'asr'}]}}}
SPOKEN = {'events': [
    {'tStartMs': 0, 'segs': [{'utf8': 'open word'}, {'utf8': ' and click insert'}]},
    {'tStartMs': 4000, 'segs': [{'utf8': '\n'}]},
    {'tStartMs': 30000, 'segs': [{'utf8': 'then click table'}]},
    {'tStartMs': 70000, 'segs': [{'utf8': 'pick three by three'}]},
    {'tStartMs': 130000, 'segs': [{'utf8': 'type in the cells'}]}]}


def beep():
    """One second of a quiet tone as a WAV file: a sound a phone can really play."""
    out = io.BytesIO()
    with wave.open(out, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(8000)
        w.writeframes(b''.join(struct.pack('<h', int(3000 * math.sin(i * 2 * math.pi * 440 / 8000))) for i in range(8000)))
    return out.getvalue()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def give(self, code, value, kind='application/json'):
        data = value if isinstance(value, bytes) else (value if isinstance(value, str) else json.dumps(value)).encode()
        self.send_response(code)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.answer(None)

    def do_POST(self):
        self.answer(json.loads(self.rfile.read(int(self.headers.get('Content-Length') or 0)) or b'{}'))

    def answer(self, body):
        url = urlparse(self.path)
        query = parse_qs(url.query)
        key = self.headers.get('xi-api-key') or ''
        if url.path == '/youtubei/v1/search':
            return self.give(200, {} if body.get('query') == 'nothing here' else FOUND)
        if url.path == '/results':
            found = {} if query.get('search_query') == ['nothing here'] else FOUND
            return self.give(200, '<html><script>var ytInitialData = %s;</script></html>' % json.dumps(found), 'text/html')
        if url.path == '/youtubei/v1/player':
            vid = body.get('videoId')
            if vid == 'bbbbbbbbbbb':
                return self.give(200, {'playabilityStatus': {'status': 'LOGIN_REQUIRED', 'reason': 'Sign in to confirm you are not a bot'}})
            if vid == 'ccccccccccc':
                return self.give(200, {'videoDetails': {'videoId': vid, 'title': 'Silent', 'author': 'Nobody', 'lengthSeconds': '10', 'shortDescription': 'No words.'}})
            return self.give(200, PLAYED)
        if url.path == '/watch':
            return self.give(200, '<html><script>var ytInitialPlayerResponse = {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you are not a bot"}};</script>', 'text/html')
        if url.path == '/api/timedtext':
            if query.get('lang') != ['en']:
                return self.give(200, '<transcript><text start="1.5" dur="2">Hallo &amp;amp; willkommen</text></transcript>', 'text/xml')
            return self.give(200, SPOKEN if query.get('fmt') == ['json3'] else '')
        if url.path.startswith('/v1/text-to-speech/'):
            with open(sys.argv[1] + '.asked', 'a') as f:
                f.write('%s %s %s\n' % (key, url.path.rsplit('/', 1)[1], body.get('text')))
            if key == 'key-used-up-1':
                return self.give(401, {'detail': {'status': 'quota_exceeded', 'message': 'You have 0 credits left.'}})
            if key == 'key-wrong-22':
                return self.give(401, {'detail': {'status': 'invalid_api_key', 'message': 'Invalid API key'}})
            if url.path.endswith('/no-such-voice'):
                return self.give(400, {'detail': {'status': 'voice_not_found', 'message': 'A voice with that id was not found.'}})
            return self.give(200, beep(), 'audio/wav')
        if url.path == '/v1/user/subscription':
            if key == 'key-wrong-22':
                return self.give(401, {'detail': {'status': 'invalid_api_key', 'message': 'Invalid API key'}})
            return self.give(200, {'tier': 'free', 'character_count': 10000 if key == 'key-used-up-1' else 120, 'character_limit': 10000})
        self.give(404, {'error': 'not found'})


server = HTTPServer(('127.0.0.1', 0), Handler)
with open(sys.argv[1], 'w') as f:
    f.write(str(server.server_address[1]))
server.serve_forever()
