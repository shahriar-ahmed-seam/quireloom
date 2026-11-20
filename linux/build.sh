#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
build="$root/linux/build"
stage="$build/deb"
shared="$root/app/src/main/java/io/quireloom/app"
version=1.0.0
mkdir -p "$build"
rm -rf "$stage"
work=$(mktemp -d "$build/.compile.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$work/classes/io/quireloom/app" "$stage/DEBIAN" "$stage/usr/bin" "$stage/usr/share/quireloom" \
    "$stage/usr/share/applications" "$stage/usr/share/icons/hicolor/128x128/apps"
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -d "$work/classes" \
    "$shared/Epub.java" "$shared/Manuscript.java" "$root"/linux/src/io/quireloom/app/*.java
install -m 644 "$root/linux/package/quireloom.png" "$work/classes/io/quireloom/app/icon.png"
install -m 644 "$root/linux/package/xhtml-entities.dtd" "$work/classes/io/quireloom/app/"
jar --create --file "$work/quireloom.jar" --main-class io.quireloom.app.DesktopApp -C "$work/classes" .
install -m 644 "$work/quireloom.jar" "$build/quireloom.jar"
install -m 755 "$root/linux/package/quireloom" "$build/quireloom"
install -m 755 "$root/linux/package/quireloom" "$stage/usr/bin/quireloom"
install -m 644 "$work/quireloom.jar" "$stage/usr/share/quireloom/quireloom.jar"
install -m 644 "$root/linux/package/io.quireloom.Quireloom.desktop" "$stage/usr/share/applications/"
install -m 644 "$root/linux/package/quireloom.png" \
    "$stage/usr/share/icons/hicolor/128x128/apps/io.quireloom.Quireloom.png"
cat > "$stage/DEBIAN/control" <<PACKAGE
Package: quireloom
Version: $version
Section: editors
Priority: optional
Architecture: all
Maintainer: Quireloom Project
Depends: openjdk-17-jre | openjdk-21-jre | java17-runtime
Recommends: poppler-utils
Description: Offline EPUB 3.3 studio, omnibus merger, cover designer & package editor
 Author manuscripts in Markdown or plain text, import rich EPUBs with covers
 and illustrations, design bookstore-grade covers, losslessly merge or split
 EPUB 2/3 books without Calibre, inspect and auto-fix EPUB packages, and
 publish valid EPUB 3.3 books from Linux desktop or CLI.
PACKAGE
dpkg-deb --root-owner-group --build "$stage" "$build/quireloom_${version}_all.deb"
