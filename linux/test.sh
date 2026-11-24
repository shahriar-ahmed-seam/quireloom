#!/bin/sh
set -eu
folder=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
"$folder/build.sh"
jar="$folder/build/quireloom.jar"
test_dir=$(mktemp -d "$folder/build/test.XXXXXX")
mkdir -p "$test_dir/classes" "$test_dir/fixtures" "$test_dir/package"
javac --release 17 -encoding UTF-8 -Xlint:all,-serial -cp "$jar" -d "$test_dir/classes" \
    "$folder/test/io/quireloom/app/ReleaseTest.java"
java -Djava.awt.headless=true -cp "$jar:$test_dir/classes" io.quireloom.app.ReleaseTest "$test_dir/fixtures"
dpkg-deb -x "$folder/build/quireloom_1.0.1_all.deb" "$test_dir/package"
cli="$test_dir/package/usr/bin/quireloom"
"$cli" --version
"$cli" --help >/dev/null
"$cli" info "$test_dir/fixtures/Export-omnibus.epub" >/dev/null
"$cli" check "$test_dir/fixtures/Export-omnibus.epub" >/dev/null
if command -v desktop-file-validate >/dev/null 2>&1; then
    desktop-file-validate "$test_dir"/package/usr/share/applications/*.desktop
fi
if [ -n "${EPUBCHECK_JAR:-}" ]; then
    for file in "$test_dir"/fixtures/Export-*.epub "$test_dir"/fixtures/split-out/*.epub; do
        [ -f "$file" ] || continue
        printf 'EPUBCheck: %s\n' "$(basename "$file")"
        java -jar "$EPUBCHECK_JAR" "$file"
    done
fi
printf 'Test artifacts: %s\n' "$test_dir"
