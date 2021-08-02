import chalk from 'chalk';
import fs from 'fs-extra';
import path from 'path';

import * as Directories from '../Directories';

const EXPO_DIR = Directories.getExpoRepositoryRootDir();

function formatJavaType(value) {
  if (value == null) {
    return 'String';
  } else if (typeof value === 'string') {
    return 'String';
  } else if (typeof value === 'number') {
    return 'int';
  }
  throw new Error(`Unsupported literal value: ${value}`);
}

function formatJavaLiteral(value) {
  if (value == null) {
    return 'null';
  } else if (typeof value === 'string') {
    value = value.replace(/"/g, '\\"');
    return `"${value}"`;
  } else if (typeof value === 'number') {
    return value;
  }
  throw new Error(`Unsupported literal value: ${value}`);
}

async function readExistingSourceAsync(filepath) {
  try {
    return await fs.readFile(filepath, 'utf8');
  } catch (e) {
    return null;
  }
}

export async function generateAndroidBuildConstantsFromMacrosAsync(macros) {
  let source;

  // android falls back to published dev home if local dev home
  // doesn't exist or had an error.
  const isLocalManifestEmpty =
    !macros.BUILD_MACHINE_KERNEL_MANIFEST || macros.BUILD_MACHINE_KERNEL_MANIFEST === '';

  let versionUsed = 'local';
  if (process.env.USE_DOGFOODING_PUBLISHED_KERNEL_MANIFEST) {
    macros.BUILD_MACHINE_KERNEL_MANIFEST = macros.DOGFOODING_PUBLISHED_KERNEL_MANIFEST;
    versionUsed = 'doogfooding';
  } else if (isLocalManifestEmpty) {
    macros.BUILD_MACHINE_KERNEL_MANIFEST = macros.DEV_PUBLISHED_KERNEL_MANIFEST;
    versionUsed = 'published dev';
  }
  console.log(`Using ${chalk.yellow(versionUsed)} version of Expo Home.`);

  delete macros['DEV_PUBLISHED_KERNEL_MANIFEST'];
  delete macros['DOGFOODING_PUBLISHED_KERNEL_MANIFEST'];

  const definitions = Object.entries(macros).map(
    ([name, value]) =>
      `  public static final ${formatJavaType(value)} ${name} = ${formatJavaLiteral(value)};`
  );

  source = `
package host.exp.exponent.generated;

public class ExponentBuildConstants {
${definitions.join('\n')}
}`;

  return (
    `
// Copyright 2016-present 650 Industries. All rights reserved.
// @generated by \`expotools android-generate-dynamic-macros\`

${source.trim()}
`.trim() + '\n'
  );
}

async function updateBuildConstants(buildConstantsPath, macros) {
  console.log(
    'Generating build config %s ...',
    chalk.cyan(path.relative(EXPO_DIR, buildConstantsPath))
  );

  const [source, existingSource] = await Promise.all([
    generateAndroidBuildConstantsFromMacrosAsync(macros),
    readExistingSourceAsync(path.resolve(buildConstantsPath)),
  ]);

  if (source !== existingSource) {
    await fs.ensureDir(path.dirname(buildConstantsPath));
    await fs.writeFile(buildConstantsPath, source, 'utf8');
  }
}

export default class AndroidMacrosGenerator {
  async generateAsync(options): Promise<void> {
    const { buildConstantsPath, macros } = options;

    await updateBuildConstants(path.resolve(buildConstantsPath), macros);
  }

  async cleanupAsync(options): Promise<void> {
    // Nothing to clean on Android
  }
}
