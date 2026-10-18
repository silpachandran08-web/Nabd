#!/usr/bin/env bash
# Needs external action: run once by a repo admin. Usage: apply-branch-protection.sh OWNER/REPO [branch]
set -euo pipefail
repo=${1:?OWNER/REPO}; branch=${2:-main}
d=$(dirname "$0")/../../.github/branch-protection.json
gh api -X PUT "repos/$repo/branches/$branch/protection" --input <(python3 - "$d" <<'PY'
import json,sys
j=json.load(open(sys.argv[1])); sig=j.pop("required_signatures")
print(json.dumps(j))
PY
)
[ "$(python3 -c "import json;print(json.load(open('$d'))['required_signatures'])")" = True ] && \
  gh api -X POST "repos/$repo/branches/$branch/protection/required_signatures" -H "Accept: application/vnd.github+json" >/dev/null
echo "protection applied to $repo:$branch"
