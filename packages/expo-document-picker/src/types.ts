// @needsAudit
/**
 *
 */
export type DocumentPickerOptions = {
  /**
   * The [MIME type](https://en.wikipedia.org/wiki/Media_type) of the documents that are available
   * to be picked. Is also supports wildcards like `'image/*'` to choose any image. To allow any type
   * of document you can use `'&ast;/*'`.
   * @default `'&ast;/*'`
   */
  type?: string;
  /**
   * If `true`, the picked file is copied to [`FileSystem.CacheDirectory`](filesystem.md#filesystemcachedirectory),
   * which allows other Expo APIs to read the file immediately. This may impact performance for
   * large files, so you should consider setting this to `false` if you expect users to pick
   * particularly large files and your app does not need immediate read access.
   * @default `true`
   */
  copyToCacheDirectory?: boolean;
  /**
   * __Web Only.__ Allows multiple files to be selected from the system UI.
   * @default `false`
   */
  multiple?: boolean;
};

// @needsAudit @docsMissing
export type DocumentResult =
  | { type: 'cancel' }
  | {
      type: 'success';
      /**
       * Document original name.
       */
      name: string;
      /**
       * Document size in bytes.
       */
      size?: number;
      /**
       * An URI to the local document file.
       */
      uri: string;
      /**
       * Document MIME type.
       */
      mimeType?: string;
      lastModified?: number;
      file?: File;
      output?: FileList | null;
    };
