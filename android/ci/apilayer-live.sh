#!/bin/bash
# Asks the real APILayer (the marketplace pages and the gateway api.apilayer.com) the way the
# `apilayer` tool does (android/assets/guest/kit) and writes down what it answered. There is no
# key here, so this shows only what a caller without a key, or with a wrong one, is told, and
# whether the list of APIs can be read. It never fails a build: it is a record.
#   apilayer-live.sh FOLDER_WITH_kit OUT_FOLDER
kit=$1/kit
out=$2
mkdir -p "$out"
work=$(mktemp -d)
mkdir -p "$work/bin" "$work/home"
cp "$kit" "$work/bin/apilayer"; chmod 755 "$work/bin/apilayer"
export PATH="$work/bin:$PATH" TERMANT_KIT_DIR="$work/kit" HOME="$work/home" TERMANT_PHONE=false
page() { # NAME URL: the page as curl gets it, with its headers
  echo "-- $1: $2"
  curl -s -L -m 60 -A "termant/1 (+https://github.com/siddhantakakhael-cyber/Termant)" -o "$out/$1" -D "$out/$1.headers" -w 'status=%{http_code} bytes=%{size_download} seconds=%{time_total}\n' "$2"
}
gateway() { # NAME, then curl arguments
  local name=$1; shift
  echo "-- $name"
  curl -s -m 60 -o "$out/$name.body" -D "$out/$name.headers" -w 'status=%{http_code} bytes=%{size_download}\n' "$@"
  head -c 600 "$out/$name.body" | tr -c '[:print:]\n' '.'; echo
}
{
  date -u
  echo "== the marketplace pages"
  page market-home.html "https://marketplace.apilayer.com/"
  page market-search.html "https://marketplace.apilayer.com/?q=currency"
  page market-none.html "https://marketplace.apilayer.com/?q=zzqqxxnothing"
  page api-exchangerates.html "https://marketplace.apilayer.com/exchangerates_data-api"
  page api-image-to-text.html "https://marketplace.apilayer.com/image_to_text-api"
  page api-pika.html "https://marketplace.apilayer.com/pika-api"
  page api-ipstack.html "https://marketplace.apilayer.com/ipstack-api"
  page api-none.html "https://marketplace.apilayer.com/zzqqxxnothing-api"
  page sitemap.xml "https://marketplace.apilayer.com/sitemap.xml"
  page robots.txt "https://marketplace.apilayer.com/robots.txt"
  echo "== the same page asked by Python, as the tool asks"
  python3 - <<'PY'
import urllib.request, urllib.error
for agent in (None, "termant/1 (+https://github.com/siddhantakakhael-cyber/Termant)"):
    r = urllib.request.Request("https://marketplace.apilayer.com/?q=currency", headers={"User-Agent": agent} if agent else {})
    try:
        with urllib.request.urlopen(r, timeout=60) as f:
            print("agent=%r status=%s bytes=%d" % (agent, f.status, len(f.read())))
    except urllib.error.HTTPError as e:
        print("agent=%r status=%s %r" % (agent, e.code, e.read(200)))
    except Exception as e:
        print("agent=%r failed: %s" % (agent, e))
PY
  echo "== the gateway"
  gateway gw-no-key "https://api.apilayer.com/exchangerates_data/latest?base=USD&symbols=INR"
  gateway gw-wrong-key -H "apikey: 0000000000000000000000000000wrong" "https://api.apilayer.com/exchangerates_data/latest?base=USD&symbols=INR"
  gateway gw-no-such-api -H "apikey: 0000000000000000000000000000wrong" "https://api.apilayer.com/zzqqxxnothing/latest"
  gateway gw-no-such-api-no-key "https://api.apilayer.com/zzqqxxnothing/latest"
  gateway gw-root "https://api.apilayer.com/"
  gateway gw-post-wrong-key -X POST -H "apikey: 0000000000000000000000000000wrong" --data-binary "hello" "https://api.apilayer.com/sentiment/analysis"
  if [ -x "$work/bin/apilayer" ] && apilayer help > /dev/null 2>&1; then
    echo "== the tool"
    try() { local name=$1 began=$SECONDS; shift; echo "-- $name: apilayer $*"; apilayer "$@" 2>&1 | head -n 60; echo "$name: exit=${PIPESTATUS[0]} seconds=$((SECONDS - began))" | tee -a "$out/summary.txt"; }
    try no-key get exchangerates_data/latest base=USD symbols=INR
    try list-currency list currency
    try list-all list
    try about about exchangerates_data
    try about-image-to-text about image_to_text
    try about-pika about pika
    try about-none about zzqqxxnothing
    apilayer key 0000000000000000000000000000wrong > /dev/null 2>&1
    try wrong-key get exchangerates_data/latest base=USD symbols=INR
    try no-such-api get zzqqxxnothing/latest
  fi
} > "$out/apilayer-live.txt" 2>&1
tail -n 80 "$out/apilayer-live.txt"
exit 0
