# Chipster command line client

## Installation

```bash
npm install chipster-cli-js
```

## Usage

```bash
chipster --help
```

## Development

Compile

```bash
tsc
```

Run

```bash
node lib/chipster
```

Update depenedencies

```bash
for p in $(cat package.json | jq -r '.dependencies | keys[]'); do npm install $p@latest --save; done
for p in $(cat package.json | jq -r '.devDependencies | keys[]'); do npm install $p@latest --save-dev; done
```
