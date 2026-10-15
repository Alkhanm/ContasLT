import type { Component } from 'vue'
import {
  Baby, Banknote, Bike, Book, Briefcase, Bus, Car, CircleDashed, CircleEllipsis, CirclePlus, Coffee,
  CreditCard, Dog, Droplets, Dumbbell, Flame, Fuel, Gamepad2, Gift, GraduationCap, Hammer, HandCoins,
  HeartPulse, House, Landmark, Laptop, Music, PiggyBank, Pill, Plane, PlugZap, Popcorn, Receipt, Repeat,
  Scissors, Shirt, ShoppingBag, ShoppingCart, Smartphone, Stethoscope, Tag, TrendingUp, Tv, Umbrella,
  Utensils, Wallet, Wifi, Wrench, Zap,
} from 'lucide-vue-next'

export const categoryIcons: Record<string, Component> = {
  'house': House,
  'plug-zap': PlugZap,
  'shopping-cart': ShoppingCart,
  'shopping-bag': ShoppingBag,
  'utensils': Utensils,
  'coffee': Coffee,
  'car': Car,
  'bus': Bus,
  'bike': Bike,
  'fuel': Fuel,
  'plane': Plane,
  'heart-pulse': HeartPulse,
  'stethoscope': Stethoscope,
  'pill': Pill,
  'graduation-cap': GraduationCap,
  'book': Book,
  'repeat': Repeat,
  'wifi': Wifi,
  'smartphone': Smartphone,
  'tv': Tv,
  'popcorn': Popcorn,
  'gamepad-2': Gamepad2,
  'music': Music,
  'gift': Gift,
  'shirt': Shirt,
  'scissors': Scissors,
  'dumbbell': Dumbbell,
  'baby': Baby,
  'dog': Dog,
  'droplets': Droplets,
  'zap': Zap,
  'flame': Flame,
  'wrench': Wrench,
  'hammer': Hammer,
  'umbrella': Umbrella,
  'landmark': Landmark,
  'credit-card': CreditCard,
  'receipt': Receipt,
  'briefcase': Briefcase,
  'laptop': Laptop,
  'trending-up': TrendingUp,
  'piggy-bank': PiggyBank,
  'wallet': Wallet,
  'banknote': Banknote,
  'hand-coins': HandCoins,
  'circle-plus': CirclePlus,
  'circle-ellipsis': CircleEllipsis,
  'circle-dashed': CircleDashed,
  'tag': Tag,
}

export const iconNames = Object.keys(categoryIcons)

export function iconFor(name: string | null | undefined): Component {
  return (name && categoryIcons[name]) || Tag
}

export const colorPalette = [
  '#1F5F43', '#2E8B57', '#0F766E', '#3D8361', '#3A6EA5', '#2563EB', '#6B4FBB', '#8A5CF6',
  '#B5537A', '#C2452D', '#D9622B', '#C98A2B', '#E0A526', '#6B7280', '#4B5563', '#9CA3AF',
]
