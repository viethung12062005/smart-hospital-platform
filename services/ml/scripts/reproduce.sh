#!/usr/bin/env bash
# Reproducibility check for the training image (MLO-02): run dataset generation and training twice, each time in a fresh
# container with no network, with the same seed, and compare the dataset hash and the model hash.
#   scripts/reproduce.sh <image> [seed]       e.g. scripts/reproduce.sh shp-ml-train:local 42
# Exit code 0 when both hashes are identical in the two runs, 1 otherwise. Needs only Docker.
set -euo pipefail

image="${1:?usage: reproduce.sh <image> [seed]}"
seed="${2:-42}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for run in 1 2; do
  mkdir -p "$work/$run"
  docker run --rm --network none --user "$(id -u):$(id -g)" -v "$work/$run:/work" "$image" \
    shp_ml.synth --out data/noshow.parquet --seed "$seed" > /dev/null
  docker run --rm --network none --user "$(id -u):$(id -g)" -v "$work/$run:/work" "$image" \
    shp_ml.pipeline --data data/noshow.parquet --registry runs/registry --seed "$seed" > "$work/$run/result.json"
  {
    echo "run $run: dataset $(sha256sum "$work/$run/data/noshow.parquet" | cut -d' ' -f1)"
    echo "run $run: model   $(sha256sum "$work/$run/runs/registry/models/1/model.txt" | cut -d' ' -f1)"
    echo "run $run: gate    $(python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); print(r["gatePassed"], r["auc"], r["baselineAuc"])' "$work/$run/result.json")"
  } | tee -a "$work/summary.txt"
done

status=0
for what in dataset model gate; do
  if [ "$(grep -c " $what " "$work/summary.txt")" -ne 2 ] || [ "$(grep " $what " "$work/summary.txt" | cut -d' ' -f4- | sort -u | wc -l)" -ne 1 ]; then
    echo "DIFFERENT: $what" >&2
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "IDENTICAL: dataset, model and metrics are the same in both containers (seed $seed)"
exit "$status"
