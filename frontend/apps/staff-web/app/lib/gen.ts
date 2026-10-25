// FEF-017: view-model helpers over the generated API types. springdoc marks no response field `required`, so a page
// states which fields the server always sends (Full) and which of them may be null (Nul). Never a second copy of a DTO.
export type Full<T, Nul extends keyof T = never> = {
  [P in keyof T]-?: P extends Nul ? Exclude<T[P], undefined> | null : Exclude<T[P], undefined>;
};
