# APTrade

An ultra-premium native investment platform for macOS.

Comparable to:

* Apple Stocks
* TradingView
* Yahoo Finance Premium
* Scalable Capital
* Interactive Brokers
* Wealthfront

Mission:

Build the most beautiful and powerful native investment platform for macOS.

Focus:

Professional portfolio management and market intelligence.

This is NOT a high-frequency trading platform.

This is NOT Bloomberg Terminal.

This is a premium desktop investing experience.

---

# Supported Assets

Phase 1:

* Stocks
* ETFs
* Cryptocurrency

Examples:

Stocks:

* AAPL
* MSFT
* NVDA
* TSLA
* AMZN

ETFs:

* SPY
* QQQ
* VOO
* VTI

Crypto:

* BTC
* ETH
* SOL
* BNB
* XRP

---

# Tech Stack

Frontend:

* SwiftUI
* AppKit
* Combine
* Charts Framework
* Metal

Backend:

* Supabase

Database:

* PostgreSQL

Authentication:

* Supabase Auth
* Apple Sign In

Storage:

* Supabase Storage

Realtime:

* WebSocket

---

# Architecture

Mandatory:

Clean Architecture

Layers:

Presentation

↓

Application

↓

Domain

↓

Infrastructure

Dependency Rule:

Outer layers depend on inner layers.

Inner layers never know infrastructure.

No shortcuts.

---

# Presentation Layer

Use:

* SwiftUI
* MVVM
* Observable State
* Async/Await

Never:

* Business logic in Views
* Networking in Views
* Massive ViewModels

Views are declarative only.

---

# Domain Layer

Contains:

Entities:

* Asset
* Portfolio
* Position
* Watchlist
* Transaction
* MarketQuote
* NewsArticle
* PriceAlert

Value Objects:

* Money
* Percentage
* Quantity
* Currency

Rules:

* No framework imports.
* No networking.
* No persistence.
* Pure business logic only.

---

# Application Layer

Contains:

Use Cases:

* FetchMarketQuotes
* SearchAssets
* AddToWatchlist
* RemoveFromWatchlist
* BuyAsset
* SellAsset
* FetchPortfolio
* FetchHistoricalPrices
* FetchNews
* CreatePriceAlert

Application orchestrates business flows.

---

# Infrastructure Layer

Contains:

Repositories:

* AssetRepository
* PortfolioRepository
* MarketRepository
* NewsRepository

Remote APIs:

Stocks:

Alpha Vantage API

Crypto:

CoinGecko API

News:

Finnhub API

Persistence:

Supabase PostgreSQL

Caching:

In-memory cache

---

# Features

Dashboard:

Show:

* Portfolio Value
* Daily Gain/Loss
* Total Return
* Asset Allocation
* Top Movers
* Market Summary

---

# Watchlist

Features:

* Unlimited watchlists
* Search assets
* Reorder items
* Live prices
* Daily percentage change

Supported:

Stocks

ETFs

Crypto

---

# Portfolio

Track:

* Holdings
* Average Cost
* Market Value
* Unrealized PnL
* Realized PnL
* Total Return
* Asset Allocation

Charts:

* Portfolio growth
* Allocation pie chart
* Historical value

---

# Charts

Premium interactive charts.

Timeframes:

* 1D
* 1W
* 1M
* 3M
* 6M
* YTD
* 1Y
* 5Y
* MAX

Indicators:

* SMA
* EMA
* RSI
* MACD
* Bollinger Bands
* VWAP

Interactions:

* Zoom
* Pan
* Crosshair
* Tooltip
* Candlestick
* Area chart

Rendering:

Metal accelerated.

60+ FPS minimum.

---

# Cryptocurrency

Supported:

Bitcoin

Ethereum

Solana

BNB

XRP

Data Source:

CoinGecko

Features:

* Prices
* Historical charts
* Market cap
* Volume
* Rank
* Percentage changes

---

# News

Provide:

* Company news
* Market news
* Crypto news

Features:

* Search
* Bookmark
* Categories
* Article preview

---

# Alerts

Support:

Price Above

Price Below

Percentage Change

Volume Spike

Delivery:

* Native macOS notifications
* In-app notifications

---

# Security

Mandatory:

Apple Keychain

AES 256 Encryption

Biometric Login

Face ID

Touch ID

Secure Enclave

Certificate Pinning

JWT Rotation

HTTPS Only

No secrets inside source code.

---

# Database Schema

Tables:

users

watchlists

watchlist_items

portfolios

positions

transactions

alerts

market_cache

news_cache

---

# Performance Targets

Cold Launch:

< 2 seconds

Search:

< 100 ms

Portfolio Calculation:

< 50 ms

Chart Rendering:

60 FPS minimum

Watchlist Updates:

Realtime

---

# UI Philosophy

Design Quality:

Apple level polish.

Inspired by:

Apple Stocks

TradingView

Linear

Stripe

Clean.

Elegant.

Dark mode first.

Subtle animations.

Premium typography.

Perfect spacing.

Zero clutter.

---

# Code Standards

NO:

Massive ViewModels

NO:

Business logic in UI

NO:

Singleton abuse

NO:

Force unwraps

NO:

God objects

ALWAYS:

SOLID

DRY

Clean Architecture

Dependency Injection

Protocols everywhere

100% testable

---

# Engineering Operating Rules

Persona:

Principal Engineer.

15+ years experience.

Expert in:

* SwiftUI
* macOS
* Finance
* Trading platforms
* System architecture

Mandatory:

1. Production-quality code only.
2. No toy implementations.
3. Full architecture for every feature.
4. Dependency Injection everywhere.
5. Strong typing.
6. Comprehensive error handling.
7. Async/Await preferred.
8. Testable architecture.
9. Elegant APIs.
10. Prioritize maintainability and user experience.

Every generated feature must be ready for a real production application.
