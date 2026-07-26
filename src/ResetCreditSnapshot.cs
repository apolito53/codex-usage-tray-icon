using System;

namespace CodexUsageTray
{
    /// <summary>
    /// Read-only presentation data for one free Codex rate-limit reset. Opaque
    /// backend identifiers stay inside the protocol response and are never
    /// retained because the tray only needs to explain when a credit expires.
    /// </summary>
    internal sealed class ResetCreditSnapshot
    {
        internal ResetCreditSnapshot(DateTime? expiresAtLocal)
        {
            ExpiresAtLocal = expiresAtLocal;
        }

        internal DateTime? ExpiresAtLocal { get; private set; }
    }
}

