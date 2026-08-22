-- Section 10.1's job creation request includes a "parameters" object
-- (e.g. width/height for image generation), which the original Section 11.2
-- table design omitted. This migration closes that gap.
ALTER TABLE jobs ADD COLUMN parameters JSONB;