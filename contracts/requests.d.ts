// Generated from OpenAPI. Response maps stay Record<string, unknown>.
export interface AccountStatus {
  status: string;
}

export interface Address {
  city: string;
  defaultAddress: boolean;
  phone: string;
  provinceCode?: string;
  recipientName: string;
  street: string;
  ward: string;
  wardCode?: string;
}

export interface Adjustment {
  delta: number;
  reason: string;
  variantId: string;
}

export interface Analysis {
  productAssetIds: Array<string>;
  referenceAssetIds: Array<string>;
}

export interface Approval {
  bindings: Array<Binding>;
}

export interface Assignment {
  shipperId: string;
}

export interface Batch {
  addresses: Array<Conversion>;
}

export interface Binding {
  sourceAssetId: string;
  variantId: string;
}

export interface Category {
  description?: string;
  name: string;
  parentId?: string;
  position: number;
}

export interface Checkout {
  addressId?: string;
  cashReceived?: number;
  customerId?: string;
  items: Array<Item>;
  voucher?: string;
}

export interface Conversion {
  districtCode: string;
  provinceCode: string;
  street?: string;
  wardCode?: string;
}

export interface ErrorBody {
  correlationId: string;
  details: Record<string, unknown>;
  errorCode: string;
  message: string;
  retryable: boolean;
}

export interface Forgot {
  email: string;
}

export interface Interaction {
  status: string;
}

export interface Item {
  quantity: number;
  variantId: string;
}

export interface Login {
  password: string;
  username: string;
}

export interface Moderation {
  reply?: string;
  status: string;
}

export interface Outfit {
  firstVariantId: string;
  secondVariantId?: string;
  tucked: boolean;
}

export interface Password {
  currentPassword: string;
  newPassword: string;
}

export interface Person {
  assetId: string;
  vtonCategory?: string;
}

export interface Product {
  categoryId: string;
  description?: string;
  fit: string;
  length: string;
  material?: string;
  name: string;
  pattern: string;
  subtype: string;
  vtonCategory: string;
  waist: string;
}

export interface Profile {
  currentPassword?: string;
  email?: string;
  fullName: string;
  phone?: string;
}

export interface Promotion {
  code: string;
  endsAt: string;
  maxDiscount?: number;
  minimumTotal: number;
  name: string;
  quantity: number;
  startsAt: string;
  type: string;
  value: number;
}

export interface Register {
  email: string;
  fullName?: string;
  password: string;
  phone: string;
  username: string;
}

export interface Reset {
  email: string;
  newPassword: string;
  otp: string;
  requestId: string;
}

export interface Review {
  comment: string;
  orderLineId: string;
  stars: number;
}

export interface Roles {
  roleIds: Array<string>;
}

export interface Route {
  garmentLength: string;
  garmentSubtype?: string;
  personFrame: string;
  vtonCategory: string;
}

export interface ShiftMoney {
  amount: number;
  note?: string;
}

export interface Staff {
  account: Register;
  roleIds: Array<string>;
}

export interface Token {
  refreshToken: string;
}

export interface TryOn {
  consent: boolean;
  personAssetId: string;
  samples: number;
  seed: number;
  steps: number;
  variantId: string;
}

export interface Unit {
  code?: string;
  districtCode?: string;
  name?: string;
  provinceCode?: string;
  type?: string;
}

export interface Variant {
  colorName: string;
  colors: string;
  price: number;
  salePrice?: number;
  shelf?: string;
  size: string;
  sku: string;
  status: string;
}