"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import styles from "../checkout.module.css";
import { formatMoney } from "../../lib/money";
import BillingStatePill from "../../components/ui/BillingStatePill";
import StockPill, { stockErrorMessage } from "../../components/ui/StockPill";
import { getIdentity } from "../../lib/session";
import { can } from "../../lib/permissions";
import { paymentMethodLabel } from "../../components/checkout/paymentMethods";
import TenderRows from "../../components/checkout/TenderRows";
import { filled, tenderError, tenderTotalMinor, toMinor, type Tender } from "../../components/checkout/tender";
import { apiFetch, goLogin } from "../../lib/apiClient";
import { moveBody, moveErrorMessage } from "../../lib/queueMove";
import { PRICE_REASONS, reasonLabel } from "../../components/checkout/priceReasons";
