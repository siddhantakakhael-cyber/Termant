#!/bin/bash
# Temporary probe: what does Mirage say about real photos (things and where they are)?
out="$PWD/probe-out"; mkdir -p "$out" work/zig work/py photos
python3 -m pip install -q --disable-pip-version-check --target work/zig ziglang==0.17.1
python3 -m pip install -q --disable-pip-version-check --target work/py pillow
base=https://huggingface.co/Cactus-Compute/cactus/resolve/f4ab2741203a282c6e7b89983de2b5e5b2e805a8/releases/4.1.4
curl -sSL --fail -o work/libcactus.a $base/linux-arm64/libcactus.a
curl -sSL --fail -o work/mirage.cact https://huggingface.co/Cactus-Compute/mirage/resolve/76c1e9e53381aca8fc3843c636216c96ff220122/mirage.cact
PYTHONPATH=work/zig python3 -m ziglang c++ -target aarch64-linux-musl -O2 -static -s -Wl,-z,stack-size=8388608 -x c .github/probe/reader.c -x none work/libcactus.a -o work/reader
raw=https://raw.githubusercontent.com
while read -r name url; do curl -sSL --fail -o "photos/$name" "$url" || echo "no $name"; done <<EOF
bus.jpg $raw/ultralytics/yolov5/master/data/images/bus.jpg
zidane.jpg $raw/ultralytics/yolov5/master/data/images/zidane.jpg
dog.jpg $raw/pjreddie/darknet/master/data/dog.jpg
horses.jpg $raw/pjreddie/darknet/master/data/horses.jpg
kite.jpg $raw/pjreddie/darknet/master/data/kite.jpg
giraffe.jpg $raw/pjreddie/darknet/master/data/giraffe.jpg
fruits.jpg $raw/opencv/opencv/4.x/samples/data/fruits.jpg
home.jpg $raw/opencv/opencv/4.x/samples/data/home.jpg
messi.jpg $raw/opencv/opencv/4.x/samples/data/messi5.jpg
building.jpg $raw/opencv/opencv/4.x/samples/data/building.jpg
coffee.png $raw/scikit-image/scikit-image/main/skimage/data/coffee.png
cat.png $raw/scikit-image/scikit-image/main/skimage/data/chelsea.png
astronaut.png $raw/scikit-image/scikit-image/main/skimage/data/astronaut.png
EOF
ls -l photos
PYTHONPATH=work/py python3 .github/probe/photos.py "$out"
