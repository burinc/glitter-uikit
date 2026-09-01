#!/usr/bin/env bash
# Assertions this project's documentation build must satisfy.
#
# Run by the shared site workflow in jlt-commons/ci-builds against the freshly
# built _site, with BASE_PATH exported. Lifted verbatim out of this repo's own
# site.yml when the build scaffolding moved to the shared workflow, so every
# check here predates that move and still means what it did.
#
# Run it locally the same way:
#   bb site:build && BASE_PATH=/glitter-uikit bash docs/check-site.sh

set -euo pipefail
out=_site

test -f "$out/index.html"       || { echo "no homepage generated"; exit 1; }
test -f "$out/guide/index.html" || { echo "no guide page generated"; exit 1; }
test -f "$out/css/screen.css"   || { echo "static assets missing"; exit 1; }

# This project has no home template, so the homepage IS the rendered
# docs/guide/index.md. If that ever stops being true silently, the
# site loses its front page without failing anything else.
grep -q 'glitter-uikit' "$out/index.html" \
  || { echo "homepage rendered but has no content"; exit 1; }

# A missing asset dir is a warning inside the engine, deliberately,
# so it has to be an error here or the docs publish with every
# embedded demo broken.
# This project's gallery is GIFs AND still frames, so count every
# file rather than one extension: a half-copied asset dir would
# otherwise pass while half the images 404.
assets=$(find "$out/demos" -type f 2>/dev/null | wc -l | tr -d ' ')
source_assets=$(find docs/demos -type f | wc -l | tr -d ' ')
test "$assets" = "$source_assets" \
  || { echo "copied $assets demo assets, expected $source_assets"; exit 1; }

! grep -rq '{{site-base}}' "$out"/index.html "$out"/guide/*.html \
  || { echo "unrendered template variable"; exit 1; }

# Both directions matter. Since engine v0.2.0 the 3.4 MB mermaid
# bundle loads only where a diagram exists: a page with one that
# does not load it renders unstyled source text, and a page without
# one that does costs every reader 3.4 MB for nothing.
grep -q 'pre class="mermaid"' "$out/guide/architecture.html" \
  || { echo "mermaid fences were not rewritten"; exit 1; }
grep -q 'mermaid.min.js' "$out/guide/architecture.html" \
  || { echo "a page with a diagram is not loading mermaid"; exit 1; }
! grep -q 'mermaid.min.js' "$out/guide/limitations.html" \
  || { echo "a page with no diagram is loading mermaid"; exit 1; }

# The failure mode the base path exists to prevent. Served at
# jlt-commons.github.io/glitter-uikit/, a root-absolute URL loads the
# ORGANIZATION site's asset instead of this project's. The page
# still renders, wearing the wrong clothes, so nothing but a check
# catches it.
if grep -ohE '(href|src)="/[^"]*"' "$out"/index.html "$out"/404.html "$out"/guide/*.html \
     | grep -vE "=\"$BASE_PATH/"; then
  echo "the URLs above escape $BASE_PATH and would resolve against the org site"
  exit 1
fi

echo "build looks correct: $assets demo assets, every URL under $BASE_PATH"
