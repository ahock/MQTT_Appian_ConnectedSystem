package de.hock.mqtt.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A collection of field-scoped {@link ValidationResult} entries produced by a validator.
 *
 * <p>Supports both construction styles used across the validation package: a validator may build a
 * {@link List} of results and wrap it with {@link #of(List)}, or create an empty accumulator with
 * the no-arg constructor and {@link #add(ValidationResult) add} entries as it goes. Callers test
 * acceptance with {@link #isValid()} / {@link #hasErrors()} and read the error entries with
 * {@link #errors()}. Entries preserve insertion order so the configuration surface can present
 * field errors in a stable sequence while retaining every entered value.
 */
public final class ValidationResults {

  private final List<ValidationResult> results;

  /** Creates an empty, mutable accumulator. */
  public ValidationResults() {
    this.results = new ArrayList<>();
  }

  private ValidationResults(List<ValidationResult> results) {
    this.results = new ArrayList<>(results);
  }

  /**
   * Wraps the given results in a collection.
   *
   * @param results the field-scoped validation results (a {@code null} list is treated as empty)
   * @return a collection containing the entries
   */
  public static ValidationResults of(List<ValidationResult> results) {
    return new ValidationResults(Objects.requireNonNullElseGet(results, List::of));
  }

  /**
   * Creates an empty collection.
   *
   * @return an empty collection
   */
  public static ValidationResults empty() {
    return new ValidationResults();
  }

  /**
   * Adds a result to the collection.
   *
   * @param result the result to record (ignored when {@code null})
   */
  public void add(ValidationResult result) {
    if (result != null) {
      results.add(result);
    }
  }

  /**
   * Merges all entries from another collection into this one, preserving order.
   *
   * @param other the collection whose entries to append (ignored when {@code null})
   */
  public void addAll(ValidationResults other) {
    if (other != null) {
      results.addAll(other.results);
    }
  }

  /**
   * Returns every result, valid and error alike, in insertion order.
   *
   * @return an unmodifiable list of all results
   */
  public List<ValidationResult> all() {
    return Collections.unmodifiableList(results);
  }

  /**
   * Returns only the entries that represent validation errors, in insertion order.
   *
   * @return an unmodifiable list of error results
   */
  public List<ValidationResult> errors() {
    List<ValidationResult> errors = new ArrayList<>();
    for (ValidationResult result : results) {
      if (result.isError()) {
        errors.add(result);
      }
    }
    return Collections.unmodifiableList(errors);
  }

  /**
   * Indicates whether any field was rejected.
   *
   * @return {@code true} when at least one result is an error
   */
  public boolean hasErrors() {
    return results.stream().anyMatch(ValidationResult::isError);
  }

  /**
   * Indicates whether every inspected field was accepted.
   *
   * @return {@code true} when no result is an error
   */
  public boolean isValid() {
    return !hasErrors();
  }
}
