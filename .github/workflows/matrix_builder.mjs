// License: Apache-2.0
// Copyright Vladimir Sitnikov, 2021
// See https://github.com/vlsi/github-actions-random-matrix
import { RNG } from './rng.mjs';

function pairKey(ai, vi, aj, vj) {
  return `${ai}:${vi}|${aj}:${vj}`;
}

class Axis {
  constructor({name, title, values}) {
    this.name = name;
    this.title = title;
    this.values = values;
    // Precompute normalized weights for pair scoring.
    // Each value's weight is normalized so that the axis weights sum to 1.
    const totalWeight = values.reduce((a, b) => a + (b.weight || 1), 0);
    this.normalizedWeights = values.map(v => (v.weight || 1) / totalWeight);
    // Map from value reference to its index for O(1) lookup
    this.valueIndex = new Map(values.map((v, i) => [v, i]));
  }

  static matches(row, filter) {
    if (typeof filter === 'function') {
      return filter(row);
    }
    if (Array.isArray(filter)) {
      // e.g. row={os: 'windows'}; filter=[{os: 'linux'}, {os: 'linux'}]
      return filter.some(v => Axis.matches(row, v));
    }
    if (typeof filter === 'object') {
      // e.g. row={jdk: {name: 'openjdk', version: 8}}; filter={jdk: {version: 8}}
      for (const [key, value] of Object.entries(filter)) {
        if (!row.hasOwnProperty(key) || !Axis.matches(row[key], value)) {
          return false;
        }
      }
      return true;
    }
    return row === filter;
  }

  pickValue(filter) {
    let values = this.values;
    if (filter) {
      values = values.filter(v => Axis.matches(v, filter));
    }
    if (values.length === 0) {
      const filterStr = typeof filter === 'string' ? filter.toString() : JSON.stringify(filter);
      throw Error(`No values produces for axis '${this.name}' from ${JSON.stringify(this.values)}, filter=${filterStr}`);
    }
    return values[Math.floor(RNG.random() * values.length)];
  }
}

class MatrixBuilder {
  constructor() {
    this.axes = [];
    this.axisByName = {};
    this.rows = [];
    this.duplicates = {};
    this.excludes = [];
    this.includes = [];
    this.implications = [];
    this.failOnUnsatisfiableFilters = false;
    this._pairsInitialized = false;
    this._uncoveredPairs = null;
    this._totalPairs = 0;
    this._totalPairsWeight = 0;
    this._uncoveredPairsWeight = 0;
  }

  /**
   * Specifies include filter (all the generated rows would comply with all the include filters)
   * @param filter
   */
  include(filter) {
    this.includes.push(filter);
  }

  /**
   * Specifies exclude filter (e.g. exclude a forbidden combination).
   * @param filter
   */
  exclude(filter) {
    this.excludes.push(filter);
  }

  /**
   * Adds implication like `antecedent -> consequent`.
   * In other words, if `antecedent` holds, then `consequent` must also hold.
   * @param antecedent
   * @param consequent
   */
  imply(antecedent, consequent) {
    this.implications.push({antecedent: antecedent, consequent: consequent});
  }

  addAxis({name, title, values}) {
    const axis = new Axis({name, title, values});
    this.axes.push(axis);
    this.axisByName[name] = axis;
    return axis;
  }

  setNamePattern(names) {
    this.namePattern = names;
  }

  /**
   * Returns true if the row matches the include and exclude filters.
   * @param row input row
   * @returns {boolean}
   */
  matches(row) {
    return (this.excludes.length === 0 || !this.excludes.some(f => Axis.matches(row, f))) &&
           (this.includes.length === 0 || this.includes.some(f => Axis.matches(row, f))) &&
           (this.implications.length === 0 || (
               this.implications.every(i => !Axis.matches(row, i.antecedent) || Axis.matches(row, i.consequent))));
  }

  failOnUnsatisfiableFilters(value) {
    this.failOnUnsatisfiableFilters = value;
  }

  /**
   * Initializes the set of all value pairs to cover.
   * Called lazily on first generateRow call (after all axes, excludes, and implications are configured).
   */
  _initPairs() {
    if (this._pairsInitialized) return;
    this._pairsInitialized = true;
    this._uncoveredPairs = new Set();
    let totalWeight = 0;
    for (let i = 0; i < this.axes.length; i++) {
      for (let j = i + 1; j < this.axes.length; j++) {
        for (let vi = 0; vi < this.axes[i].values.length; vi++) {
          const wi = this.axes[i].normalizedWeights[vi];
          for (let vj = 0; vj < this.axes[j].values.length; vj++) {
            this._uncoveredPairs.add(pairKey(i, vi, j, vj));
            totalWeight += wi * this.axes[j].normalizedWeights[vj];
          }
        }
      }
    }
    this._totalPairs = this._uncoveredPairs.size;
    this._totalPairsWeight = totalWeight;
    this._uncoveredPairsWeight = totalWeight;
  }

  /**
   * Scores a candidate row by the weighted sum of uncovered pairs it would cover.
   * Each pair's contribution is normalizedWeight_i * normalizedWeight_j,
   * so axes with different weight scales contribute fairly.
   */
  _scoreNewPairs(row) {
    let score = 0;
    for (let i = 0; i < this.axes.length; i++) {
      const axisI = this.axes[i];
      const vi = axisI.valueIndex.get(row[axisI.name]);
      const wi = axisI.normalizedWeights[vi];
      for (let j = i + 1; j < this.axes.length; j++) {
        const axisJ = this.axes[j];
        const vj = axisJ.valueIndex.get(row[axisJ.name]);
        if (this._uncoveredPairs.has(pairKey(i, vi, j, vj))) {
          score += wi * axisJ.normalizedWeights[vj];
        }
      }
    }
    return score;
  }

  /**
   * Marks all pairs in a row as covered.
   */
  _markCovered(row) {
    let weight = 0;
    for (let i = 0; i < this.axes.length; i++) {
      const vi = this.axes[i].valueIndex.get(row[this.axes[i].name]);
      const wi = this.axes[i].normalizedWeights[vi];
      for (let j = i + 1; j < this.axes.length; j++) {
        const vj = this.axes[j].valueIndex.get(row[this.axes[j].name]);
        if (this._uncoveredPairs.delete(pairKey(i, vi, j, vj))) {
          weight += wi * this.axes[j].normalizedWeights[vj];
        }
      }
    }
    this._uncoveredPairsWeight -= weight;
  }

  /**
   * Generates a single valid candidate row matching the optional filter.
   * Returns null if no valid candidate can be produced after several attempts.
   */
  _generateCandidate(filter) {
    for (let attempt = 0; attempt < 20; attempt++) {
      const row = this.axes.reduce(
        (prev, next) =>
          Object.assign(prev, {
            [next.name]: next.pickValue(filter ? filter[next.name] : undefined)
          }),
        {}
      );
      if (this.matches(row)) {
        return row;
      }
    }
    return null;
  }

  /**
   * Computes the display name for a row based on the name pattern.
   */
  _computeName(row) {
    return this.namePattern.map(axisName => {
      let value = row[axisName];
      const title = value.title;
      if (typeof title != 'undefined') {
        return title;
      }
      const computeTitle = this.axisByName[axisName].title;
      if (computeTitle) {
        return computeTitle(value);
      }
      if (typeof value === 'object' && value.hasOwnProperty('value')) {
        return value.value;
      }
      return value;
    }).filter(Boolean).join(", ");
  }

  /**
   * Adds a row that matches the given filter to the resulting matrix.
   * Among many random candidates satisfying the filter, picks the one
   * that covers the most previously-uncovered parameter pairs.
   *
   * filter values could be
   *  - literal values: filter={os: 'windows-latest'}
   *  - arrays: filter={os: ['windows-latest', 'linux-latest']}
   *  - functions: filter={os: x => x!='windows-latest'}
   * @param filter object with keys matching axes names
   * @returns {*}
   */
  generateRow(filter) {
    this._initPairs();
    if (filter) {
      // If matching row already exists, no need to generate more
      const existing = this.rows.some(v => Axis.matches(v, filter));
      if (existing) {
        return existing;
      }
    }

    const numCandidates = 1000;
    let bestRow = null;
    let bestScore = -1;

    for (let n = 0; n < numCandidates; n++) {
      const candidate = this._generateCandidate(filter);
      if (!candidate) {
        continue;
      }

      const key = JSON.stringify(candidate);
      if (this.duplicates.hasOwnProperty(key)) continue;

      const score = this._scoreNewPairs(candidate);
      if (score > bestScore) {
        bestScore = score;
        bestRow = candidate;
      }
    }

    if (bestRow) {
      const key = JSON.stringify(bestRow);
      this.duplicates[key] = true;
      bestRow.name = this._computeName(bestRow);
      this._markCovered(bestRow);
      this.rows.push(bestRow);
      return bestRow;
    }

    const filterStr = typeof filter === 'string' ? filter.toString() : JSON.stringify(filter);
    const msg = `Unable to generate row for ${filterStr}. Please check include and exclude filters`;
    if (this.failOnUnsatisfiableFilters) {
      throw Error(msg);
    } else {
      console.warn(msg);
    }
  }

  ensureAllAxisValuesCovered(axisName) {
    for (let value of this.axisByName[axisName].values) {
      this.generateRow({[axisName]: value});
    }
  }

  generateRows(maxRows, filter) {
    this._initPairs();
    for (let i = 0; this.rows.length < maxRows && i < maxRows; i++) {
      this.generateRow(filter);
    }
    return this.rows;
  }

  /**
   * Returns pair coverage statistics for the generated rows.
   * @returns {{covered: number, total: number, percentage: string, weightPercentage: string}}
   */
  pairCoverageReport() {
    this._initPairs();
    const covered = this._totalPairs - this._uncoveredPairs.size;
    const coveredWeight = this._totalPairsWeight - this._uncoveredPairsWeight;
    return {
      covered,
      total: this._totalPairs,
      percentage: (covered / this._totalPairs * 100).toFixed(1),
      weightPercentage: (coveredWeight / this._totalPairsWeight * 100).toFixed(1)
    };
  }

  /**
   * Computes the number of all the possible combinations.
   * @returns {{bad: number, good: number}}
   */
  summary() {
   let position = -1;
   let indices = [];
   let values = {};
   const axes = this.axes;
   function resetValuesUpTo(nextPosition) {
     for(let i=0; i<nextPosition; i++) {
       const axis = axes[i];
       values[axis.name] = axis.values[0];
       indices[i] = 1; // next index
     }
     position = 0;
   }

   function nextAvailablePosition() {
    let size = axes.length;
    for (let i = position; i < size; i++) {
      if (indices[i] < axes[i].values.length) {
        return i;
      }
    }
    return -1;
   }
   // The first initialization of the values
   resetValuesUpTo(this.axes.length);
   let good = 0;
   let bad = 0;
   while (true) {
     if (indices[position] < this.axes[position].values.length) {
       // Advance iterator at the current position if possible
       const axis = this.axes[position];
       values[axis.name] = axis.values[indices[position]];
       indices[position]++;
     } else {
       // Advance the next iterator, and reset [0..nextPosition)
       position++;
       let nextPosition = nextAvailablePosition();
       if (nextPosition === -1) {
         break;
       }
       const axis = this.axes[nextPosition];
       values[axis.name] = axis.values[indices[nextPosition]];
       indices[nextPosition]++;
       resetValuesUpTo(nextPosition);
     }
     if (this.matches(values)) {
       good++;
     } else {
       bad++;
     }
   }
   return {good: good, bad: bad};
  }
}

export { Axis, MatrixBuilder };
