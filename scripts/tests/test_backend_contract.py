import importlib.util
from pathlib import Path
import unittest
import tempfile
import copy

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


class ScopedGroundTruthTest(unittest.TestCase):
    def test_behavior_gate_detects_invented_values_wrong_partitions_and_extra_predictions(self):
        truth = {"customScalars": [{"model": "Note", "field": "type", "type": "string", "knownValues": ["known"]}],
                 "clientSelections": [{"method": "POST", "path": "pick", "bodyType": "Body", "jsonName": "when",
                                       "variants": {"Now": ["null", "empty"], "Later": ["nonempty"]}}]}
        contract = {"models": [{"className": "Note", "fields": [{"jsonName": "type", "enumValues": [], "wire":
                         {"type": "string", "knownValues": ["known"], "evidence": ["source"]}}]}],
                    "endpoints": [{"method": "POST", "path": "pick", "bodyType": "Body", "responseType": name,
                        "clientSelection": {"jsonName": "when", "stringStates": states, "evidence": ["source"]}}
                        for name, states in truth["clientSelections"][0]["variants"].items()]}
        baseline = evaluation.behavior_metrics(contract, truth)
        self.assertEqual(1, baseline["client_selections_matched"])
        for mutation in ["values", "states", "extra", "evidence", "enum"]:
            bad = copy.deepcopy(contract)
            if mutation == "values": bad["models"][0]["fields"][0]["wire"]["knownValues"].append("invented")
            if mutation == "states": bad["endpoints"][1]["clientSelection"]["stringStates"] = ["empty"]
            if mutation == "extra": bad["endpoints"].append({**bad["endpoints"][0], "path": "extra"})
            if mutation == "evidence": bad["endpoints"][0]["clientSelection"]["evidence"] = []
            if mutation == "enum": bad["models"][0]["fields"][0]["enumValues"] = ["known"]
            self.assertTrue(evaluation.regression_errors(evaluation.behavior_metrics(bad, truth), baseline), mutation)

    def test_nested_sealed_type_does_not_inherit_unrelated_enum_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Notification.kt").write_text('''package app
                @JsonClass(generateAdapter = true)
                data class Notification(val type: Type) {
                    sealed class Type(val name: String) { object Known : Type("known") }
                }
            ''')
            (root / "Attachment.kt").write_text('''package app
                class Attachment { enum class Type { @Json(name = "image") IMAGE, UNKNOWN } }
            ''')
            names = evaluation.declared_types(root)
            enums = evaluation.enums(root)
            context = evaluation.moshi_models(root)["app.Notification"]["type"][3]
            self.assertEqual("app.Notification.Type", evaluation.scoped_field_type(context, names))
            self.assertNotIn(evaluation.scoped_field_type(context, names), enums)
            self.assertEqual(["image", "UNKNOWN"], enums["app.Attachment.Type"])

    def test_import_alias_and_qualified_nested_enums_are_scored_against_exact_owner(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Enums.kt").write_text('''package types
                class A { enum class Type { FIRST } }
                class B { enum class Type { SECOND } }
            ''')
            (root / "Model.kt").write_text('''package app
                import types.A.Type as Choice
                import types.B
                @JsonClass(generateAdapter = true)
                class Model(val one: List<Choice>, val two: B.Type)
            ''')
            names, enums = evaluation.declared_types(root), evaluation.enums(root)
            fields = evaluation.moshi_models(root)["app.Model"]
            self.assertEqual(["FIRST"], enums[evaluation.scoped_field_type(fields["one"][3], names)])
            self.assertEqual(["SECOND"], enums[evaluation.scoped_field_type(fields["two"][3], names)])
            self.assertIsNone(evaluation.scoped_field_type(("Type", "app.Model", "app", {}), names))

    def test_literals_with_urls_braces_and_comments_do_not_corrupt_ground_truth(self):
        text = '''class Model { val url = "https://example.test/{broken" // } fake
            /* class Wrong { */ enum class Choice { A, B }
        }'''
        clean = evaluation.strip_comments(text)
        bodies = evaluation.class_bodies(clean)
        self.assertEqual(["Model", "Choice"], [b[0] for b in bodies])
        self.assertIn("https://example.test/{broken", clean)


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
