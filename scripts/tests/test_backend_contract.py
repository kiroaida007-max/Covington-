import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("check_mock", Path(__file__).resolve().parents[2] / "benchmarks/backend-contract/check_mock.py")
mock = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mock)

EVAL_SPEC = importlib.util.spec_from_file_location("contract_evaluate", Path(__file__).resolve().parents[2] / "benchmarks/backend-contract/evaluate.py")
evaluation = importlib.util.module_from_spec(EVAL_SPEC)
EVAL_SPEC.loader.exec_module(evaluation)


class AccuracyGateTest(unittest.TestCase):
    def test_gate_rejects_lower_accuracy_extra_predictions_and_lost_dataset_coverage(self):
        baseline = {"types_matched": 377, "types_total": 378, "endpoints_extra": 0}
        self.assertEqual([], evaluation.regression_errors(baseline, baseline))
        for bad in [{**baseline, "types_matched": 376}, {**baseline, "types_total": 377},
                    {**baseline, "endpoints_extra": 1}, {}]:
            self.assertTrue(evaluation.regression_errors(bad, baseline))
        self.assertEqual([], evaluation.regression_errors({**baseline, "types_matched": 378}, baseline))


class IndependentSchemaCheckTest(unittest.TestCase):
    def errors(self, value, schema, spec=None):
        errors = []
        mock.check(value, schema, spec or {}, "$", errors)
        return errors

    def test_anyof_allows_overlapping_shapes_while_oneof_requires_exactly_one(self):
        alternatives = [{"type": "object", "properties": {}}, {"type": "object", "properties": {}}]
        self.assertEqual([], self.errors({}, {"anyOf": alternatives}))
        self.assertTrue(self.errors({}, {"oneOf": alternatives}))
        self.assertTrue(self.errors(3, {"anyOf": alternatives}))

    def test_known_bad_candidates_cannot_pass_types_enums_nullability_and_map_values(self):
        for value, schema in [(True, {"type": "integer"}), (None, {"type": "string"}),
                              ("invented", {"type": "string", "enum": ["public"]}),
                              ({"x": "bad"}, {"type": "object", "additionalProperties": {"type": "integer"}}),
                              ([1], {"type": "array", "items": {"type": "boolean"}})]:
            with self.subTest(value=value):
                self.assertTrue(self.errors(value, schema))

    def test_nullable_reference_and_missing_example_fields(self):
        spec = {"components": {"schemas": {"a/b": {"type": "object", "properties": {"name": {"type": "string"}}}}}}
        schema = {"anyOf": [{"type": "object", "nullable": True, "enum": [None]}, {"$ref": "#/components/schemas/a~1b"}]}
        self.assertEqual([], self.errors(None, schema, spec))
        self.assertEqual([], self.errors({"name": "x"}, schema, spec))
        self.assertTrue(self.errors({}, schema, spec))

    def test_depth_budget_exhaustion_is_a_failure(self):
        errors = []
        mock.check(None, {}, {}, "$", errors, depth=41)
        self.assertTrue(errors)


if __name__ == "__main__":
    unittest.main()
