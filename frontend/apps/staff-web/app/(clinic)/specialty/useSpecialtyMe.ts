"use client";

import { useQuery } from "@tanstack/react-query";
import { getter, key } from "../../lib/query";
import type { MySpecialty } from "./specialtyModel";

/** GET /v1/specialty/me: one cached read shared by the specialty workspace, My Day (CLN-043) and the consult's quick-action bar. */
export const useSpecialtyMe = () => useQuery({ queryKey: key("specialty-me"), queryFn: getter<MySpecialty>("/specialty/me"), staleTime: 60_000 });
