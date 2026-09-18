// @ts-check
import eslint from "@eslint/js";
import prettier from "eslint-config-prettier";
import importPlugin from "eslint-plugin-import";
import globals from "globals";
import tseslint from "typescript-eslint";

// kept in sync with chipster-web/eslint.config.mjs, without the angular parts.
// this is a node service, so there is no browser global set and no templates.
export default tseslint.config(
  {
    // lib and lib-test hold the compiler output, test-files holds data fixtures
    ignores: ["lib/**/*", "lib-test/**/*", "test-files/**/*", "logs/**/*"],
  },
  {
    files: ["**/*.ts"],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      importPlugin.flatConfigs.recommended,
      importPlugin.flatConfigs.typescript,
    ],
    languageOptions: {
      globals: {
        ...globals.node,
      },
      parserOptions: {
        ecmaVersion: 2020,
        sourceType: "module",
      },
    },
    settings: {
      // the typescript resolver understands the exports maps of the
      // dependencies and the paths of tsconfig.json, which the node resolver
      // does not
      "import/resolver": {
        typescript: {
          project: "tsconfig.json",
        },
      },
    },
    rules: {
      // rules kept from the airbnb set that was dropped with eslint 9 in
      // chipster-web. these catch mistakes, the style-only airbnb rules are gone.
      "array-callback-return": "error",
      "consistent-return": "error",
      "default-case": "error",
      eqeqeq: ["error", "always", { null: "ignore" }],
      "guard-for-in": "error",
      "no-console": "warn",
      "no-else-return": "error",
      // rebinding a parameter hides which value a later line reads. mutating
      // the fields of one is allowed
      "no-param-reassign": "error",
      "no-return-assign": ["error", "always"],
      radix: "error",
      // imports have to be declared in package.json, so that they don't
      // depend on what other packages happen to pull in
      "import/no-extraneous-dependencies": ["error", { devDependencies: false, optionalDependencies: false }],
      // an inner variable with the name of an outer one is usually a mistake,
      // and reads as one even when it isn't
      "@typescript-eslint/no-shadow": "error",
      "import/no-cycle": "warn",

      // same as in chipster-web: not enforced before, warn to keep them
      // visible and to stop new ones spreading
      "@typescript-eslint/no-explicit-any": "warn",
      "@typescript-eslint/no-empty-object-type": "warn",

      // a leading underscore marks a binding that has to exist but isn't used
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
        },
      ],
    },
  },
  {
    // plain javascript run by node, like this config itself. these are not
    // part of tsconfig.json, so the typescript block above can't parse them.
    files: ["**/*.js", "**/*.mjs"],
    extends: [eslint.configs.recommended, importPlugin.flatConfigs.recommended],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
    settings: {
      "import/resolver": {
        typescript: true,
      },
    },
    rules: {
      // typescript-eslint's recommended set errors on both of these for
      // typescript, but nothing does for plain javascript
      "no-var": "error",
      "prefer-const": "error",
      // and these have no typescript-aware version, so the listing of the
      // typescript block doesn't reach here
      "no-param-reassign": "error",
      "no-shadow": "error",
      "import/no-extraneous-dependencies": ["error", { devDependencies: false, optionalDependencies: false }],
    },
  },
  {
    files: ["**/*.spec.ts", "eslint.config.mjs"],
    rules: {
      // tooling configs and tests import dev dependencies on purpose
      "import/no-extraneous-dependencies": ["error", { devDependencies: true, optionalDependencies: false }],
    },
  },
  prettier,
);
