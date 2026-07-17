package edu.utem.ftmk.evaluation;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class IngredientComparisonService {

    /*
     * A model ingredient must score at least 0.75
     * to be considered a match.
     */
    private static final double MATCH_THRESHOLD = 0.75;

    public JSONObject compare(
            JSONArray modelIngredients,
            JSONArray groundTruthIngredients) {

        JSONArray evaluatedModel = new JSONArray();
        JSONArray omittedGroundTruth = new JSONArray();

        Set<Integer> matchedGroundTruthIndexes =
                new HashSet<>();

        int matchedCount = 0;
        int hallucinatedCount = 0;

        for (int modelIndex = 0;
             modelIndex < modelIngredients.length();
             modelIndex++) {

            JSONObject modelIngredient =
                    new JSONObject(
                        modelIngredients
                            .getJSONObject(modelIndex)
                            .toString()
                    );

            int bestGroundTruthIndex = -1;
            double bestScore = 0.0;

            for (int gtIndex = 0;
                 gtIndex < groundTruthIngredients.length();
                 gtIndex++) {

                if (matchedGroundTruthIndexes.contains(gtIndex)) {
                    continue;
                }

                JSONObject groundTruthIngredient =
                        groundTruthIngredients
                            .getJSONObject(gtIndex);

                double score = calculateIngredientSimilarity(
                        modelIngredient,
                        groundTruthIngredient
                );

                if (score > bestScore) {
                    bestScore = score;
                    bestGroundTruthIndex = gtIndex;
                }
            }

            boolean matched =
                    bestGroundTruthIndex >= 0
                    && bestScore >= MATCH_THRESHOLD;

                    if (matched) {
                        JSONObject groundTruthMatch =
                                groundTruthIngredients.getJSONObject(
                                        bestGroundTruthIndex
                                );

                        matchedGroundTruthIndexes.add(
                                bestGroundTruthIndex
                        );

                        matchedCount++;

                        double originalScore = compareNames(
                                normalize(modelIngredient.optString(
                                        "name_original",
                                        ""
                                )),
                                normalize(groundTruthMatch.optString(
                                        "name_original",
                                        ""
                                ))
                        );

                        double englishScore = compareNames(
                                normalize(modelIngredient.optString(
                                        "name_en",
                                        ""
                                )),
                                normalize(groundTruthMatch.optString(
                                        "name_en",
                                        ""
                                ))
                        );

                        modelIngredient.put("matched", true);
                        modelIngredient.put("hallucinated", false);
                        modelIngredient.put(
                                "matched_gt_ingredient_id",
                                groundTruthMatch.optInt("gt_ingredient_id")
                        );

                        modelIngredient.put(
                                "matched_ground_truth_original",
                                groundTruthMatch.optString(
                                        "name_original",
                                        "—"
                                )
                        );

                        modelIngredient.put(
                                "matched_ground_truth_en",
                                groundTruthMatch.optString(
                                        "name_en",
                                        "—"
                                )
                        );

                        modelIngredient.put(
                                "original_match_score",
                                round(originalScore * 100.0)
                        );

                        modelIngredient.put(
                                "english_match_score",
                                round(englishScore * 100.0)
                        );

                        modelIngredient.put(
                                "translation_correct",
                                englishScore >= MATCH_THRESHOLD
                        );

                        // Keep this for compatibility with existing client code.
                        modelIngredient.put(
                                "match_score",
                                round(bestScore * 100.0)
                        );

                    } else {
                        hallucinatedCount++;

                        modelIngredient.put("matched", false);
                        modelIngredient.put("hallucinated", true);
                        modelIngredient.put(
                                "matched_gt_ingredient_id",
                                JSONObject.NULL
                        );

                        modelIngredient.put(
                                "matched_ground_truth_original",
                                JSONObject.NULL
                        );

                        modelIngredient.put(
                                "matched_ground_truth_en",
                                JSONObject.NULL
                        );

                        modelIngredient.put(
                                "original_match_score",
                                round(bestScore * 100.0)
                        );

                        modelIngredient.put(
                                "english_match_score",
                                0.0
                        );

                        modelIngredient.put(
                                "translation_correct",
                                false
                        );

                        modelIngredient.put(
                                "match_score",
                                round(bestScore * 100.0)
                        );
                    }

            evaluatedModel.put(modelIngredient);
        }

        for (int gtIndex = 0;
             gtIndex < groundTruthIngredients.length();
             gtIndex++) {

            if (!matchedGroundTruthIndexes.contains(gtIndex)) {
                omittedGroundTruth.put(
                    groundTruthIngredients
                        .getJSONObject(gtIndex)
                );
            }
        }

        int modelCount = modelIngredients.length();
        int groundTruthCount =
                groundTruthIngredients.length();

        double hallucinationRate =
                modelCount == 0
                ? 0.0
                : hallucinatedCount * 100.0 / modelCount;

        double ingredientRecall =
                groundTruthCount == 0
                ? 0.0
                : matchedCount * 100.0 / groundTruthCount;

        JSONObject result = new JSONObject();

        result.put("ingredients", evaluatedModel);

        result.put(
            "ground_truth_ingredients",
            groundTruthIngredients
        );

        result.put(
            "omitted_ground_truth_ingredients",
            omittedGroundTruth
        );

        result.put(
            "total_model_ingredients",
            modelCount
        );

        result.put(
            "total_ground_truth_ingredients",
            groundTruthCount
        );

        result.put("matched_count", matchedCount);

        result.put(
            "hallucinated_count",
            hallucinatedCount
        );

        result.put(
            "omitted_count",
            omittedGroundTruth.length()
        );

        result.put(
            "hallucination_rate",
            round(hallucinationRate)
        );

        result.put(
            "ingredient_recall",
            round(ingredientRecall)
        );

        return result;
    }

    private double calculateIngredientSimilarity(
            JSONObject modelIngredient,
            JSONObject groundTruthIngredient) {

        String modelOriginal = normalize(
                modelIngredient.optString("name_original", "")
        );

        String modelEnglish = normalize(
                modelIngredient.optString("name_en", "")
        );

        String groundTruthOriginal = normalize(
                groundTruthIngredient.optString("name_original", "")
        );

        String groundTruthEnglish = normalize(
                groundTruthIngredient.optString("name_en", "")
        );

        double originalScore = compareNames(
                modelOriginal,
                groundTruthOriginal
        );

        double englishScore = compareNames(
                modelEnglish,
                groundTruthEnglish
        );

        /*
         * Ingredient hallucination should primarily depend on
         * the original ingredient identity.
         */
        if (!modelOriginal.isBlank()
                && !groundTruthOriginal.isBlank()) {
            return originalScore;
        }

        return englishScore;
    }

    private double compareNames(
            String first,
            String second) {

        if (first.isBlank() || second.isBlank()) {
            return 0.0;
        }

        if (first.equals(second)) {
            return 1.0;
        }

        /*
         * Example:
         * "red onion" can match "onion".
         */
        if (first.length() >= 4
                && second.length() >= 4
                && (first.contains(second)
                    || second.contains(first))) {
            return 0.90;
        }

        double levenshtein =
                levenshteinSimilarity(first, second);

        double tokenSimilarity =
                tokenSimilarity(first, second);

        return Math.max(levenshtein, tokenSimilarity);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(
            value,
            Normalizer.Form.NFD
        );

        return normalized
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\([^)]*\\)", " ")
            .replaceAll("[^a-z0-9 ]", " ")
            .replaceAll(
                "\\b(fresh|chopped|sliced|diced|minced|"
                + "optional|to taste|whole|small|medium|large|"
                + "segar|dicincang|dihiris|dipotong|"
                + "secukup rasa)\\b",
                " "
            )
            .replaceAll("\\s+", " ")
            .trim();
    }

    private double tokenSimilarity(
            String first,
            String second) {

        Set<String> firstTokens =
                toTokenSet(first);

        Set<String> secondTokens =
                toTokenSet(second);

        if (firstTokens.isEmpty()
                || secondTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection =
                new HashSet<>(firstTokens);

        intersection.retainAll(secondTokens);

        Set<String> union =
                new HashSet<>(firstTokens);

        union.addAll(secondTokens);

        return intersection.size()
                / (double) union.size();
    }

    private Set<String> toTokenSet(String value) {
        Set<String> tokens = new HashSet<>();

        for (String token : value.split(" ")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    private double levenshteinSimilarity(
            String first,
            String second) {

        int distance =
                levenshteinDistance(first, second);

        int longest =
                Math.max(first.length(), second.length());

        if (longest == 0) {
            return 1.0;
        }

        return 1.0 - distance / (double) longest;
    }

    private int levenshteinDistance(
            String first,
            String second) {

        int[][] matrix =
                new int[first.length() + 1]
                       [second.length() + 1];

        for (int i = 0; i <= first.length(); i++) {
            matrix[i][0] = i;
        }

        for (int j = 0; j <= second.length(); j++) {
            matrix[0][j] = j;
        }

        for (int i = 1; i <= first.length(); i++) {
            for (int j = 1; j <= second.length(); j++) {
                int replacementCost =
                        first.charAt(i - 1)
                        == second.charAt(j - 1)
                        ? 0
                        : 1;

                matrix[i][j] = Math.min(
                    Math.min(
                        matrix[i - 1][j] + 1,
                        matrix[i][j - 1] + 1
                    ),
                    matrix[i - 1][j - 1]
                        + replacementCost
                );
            }
        }

        return matrix[first.length()][second.length()];
    }

    private String preferredName(JSONObject ingredient) {
        String english =
                ingredient.optString("name_en", "").trim();

        if (!english.isBlank()) {
            return english;
        }

        return ingredient.optString(
            "name_original",
            "—"
        );
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}