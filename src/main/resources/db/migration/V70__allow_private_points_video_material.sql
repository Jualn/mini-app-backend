-- Existing file size, ownership, immutable retention and lifecycle constraints remain in force.
ALTER TABLE media_point_file
 DROP CHECK media_point_file_chk_3,
 ADD CONSTRAINT chk_point_material_kind CHECK(kind IN ('IMAGE','PDF','WORD','VIDEO')),
 MODIFY COLUMN kind VARCHAR(8) NOT NULL COMMENT 'IMAGE/PDF/WORD/VIDEO; VIDEO is MP4, format-checked without automatic content moderation';
