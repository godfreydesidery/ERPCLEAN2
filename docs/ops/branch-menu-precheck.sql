-- Branch menu / branch price pre-check (ADR-0073). READ-ONLY.
--
-- Run on a client's database BEFORE they update to a server with the branch menu. Values typed into
-- Product Master > Branch availability in the past (Branch price, Active switched off) did nothing until
-- now; after the update they take effect. Show the client both lists and confirm or clear them first.
--
--   psql -h <host> -U <user> -d <db> -f branch-menu-precheck.sql

\echo '1) Items with a branch price (will be charged at that branch after the update)'
SELECT c.name  AS company,
       b.name  AS branch,
       p.code,
       p.name  AS item,
       pb.branch_price,
       pb.active
FROM product_branch pb
JOIN products  p ON p.id = pb.product_id
JOIN branches  b ON b.id = pb.branch_id
JOIN companies c ON c.id = p.company_id
WHERE pb.branch_price IS NOT NULL
ORDER BY c.name, b.name, p.name;

\echo '2) Items that will DISAPPEAR from a branch after the update'
\echo '   (the item has branch rows, but none active for that branch)'
SELECT c.name AS company,
       b.name AS branch,
       p.code,
       p.name AS item,
       CASE WHEN pb.product_id IS NULL THEN 'no row for this branch' ELSE 'switched off' END AS why
FROM products p
JOIN companies c ON c.id = p.company_id
JOIN branches  b ON b.company_id = p.company_id
LEFT JOIN product_branch pb ON pb.product_id = p.id AND pb.branch_id = b.id
WHERE p.status = 'ACTIVE'
  AND p.sellable
  AND EXISTS (SELECT 1 FROM product_branch any_pb WHERE any_pb.product_id = p.id)
  AND (pb.product_id IS NULL OR NOT pb.active)
ORDER BY c.name, b.name, p.name;

\echo '3) Summary per company'
SELECT c.name AS company,
       COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM product_branch x WHERE x.product_id = p.id))
           AS items_with_branch_rows,
       COUNT(*) AS items_total
FROM products p
JOIN companies c ON c.id = p.company_id
WHERE p.status = 'ACTIVE'
GROUP BY c.name
ORDER BY c.name;
