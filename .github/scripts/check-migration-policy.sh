#!/usr/bin/env bash
set -Eeuo pipefail

readonly MIGRATION_DIR="src/main/resources/db/migration"
readonly BASE_SHA="${1:-}"
readonly EVENT_NAME="${2:-unknown}"
readonly ALLOW_NO_MIGRATION="${3:-false}"

die() {
  printf 'Migration policy failed: %s\n' "$*" >&2
  exit 1
}

[[ -d "$MIGRATION_DIR" ]] || die "missing migration directory: $MIGRATION_DIR"

declare -A versions=()
mapfile -t migration_files < <(
  find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' -print | sort
)
[[ ${#migration_files[@]} -gt 0 ]] || die "no versioned migrations found"

for file in "${migration_files[@]}"; do
  filename="$(basename "$file")"
  if [[ ! "$filename" =~ ^V([1-9][0-9]*)__([a-z0-9]+(_[a-z0-9]+)*)\.sql$ ]]; then
    die "invalid filename: $filename (expected V<number>__lower_snake_case.sql)"
  fi

  version="${BASH_REMATCH[1]}"
  [[ -z "${versions[$version]:-}" ]] \
    || die "duplicate version V${version}: ${versions[$version]} and $filename"
  versions[$version]="$filename"
done

if [[ -z "$BASE_SHA" || "$BASE_SHA" =~ ^0+$ ]]; then
  printf 'Migration policy: no comparison base for %s; tree checks only.\n' "$EVENT_NAME"
  exit 0
fi

git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null \
  || die "comparison base is unavailable: $BASE_SHA"

readonly DIFF_RANGE="${BASE_SHA}...HEAD"
added_migration=0

while IFS=$'\t' read -r status first_path second_path; do
  [[ -n "$status" ]] || continue
  if [[ "$status" != A* ]]; then
    changed_path="${second_path:-$first_path}"
    die "historical migration cannot be modified, deleted, copied, or renamed: $status $changed_path"
  fi
  added_migration=1
done < <(git diff --name-status --find-renames "$DIFF_RANGE" -- "$MIGRATION_DIR")

if [[ "$EVENT_NAME" == "pull_request" ]]; then
  mapfile -t entity_changes < <(
    git diff --name-only "$DIFF_RANGE" -- \
      ':(glob)src/main/java/**/entity/*.java'
  )

  if (( ${#entity_changes[@]} > 0 && added_migration == 0 )); then
    if [[ "$ALLOW_NO_MIGRATION" == "true" ]]; then
      printf 'Migration policy: entity changes explicitly exempted by no-db-migration label.\n'
    else
      printf 'Entity changes without a new migration:\n' >&2
      printf '  %s\n' "${entity_changes[@]}" >&2
      die "add a new V<number>__description.sql, or apply the no-db-migration PR label when no schema change is required"
    fi
  fi

  if git diff --quiet "$DIFF_RANGE" -- DATABASE_DESIGN.md; then
    design_changed=0
  else
    design_changed=1
  fi

  if (( design_changed == 1 && added_migration == 0 )); then
    printf 'Migration policy warning: DATABASE_DESIGN.md changed without a new migration; verify this is documentation-only.\n'
  fi
fi

printf 'Migration policy: OK (%s versioned files).\n' "${#migration_files[@]}"
